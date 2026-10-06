package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalAction;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalRecord;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalStep;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ApprovalRecordRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 报销单生命周期服务。
 *
 * 状态机：DRAFT -> SUBMITTED -> APPROVED / REJECTED / WITHDRAWN；
 * REJECTED / WITHDRAWN 可 resubmit 回到 SUBMITTED。
 * 预算占用/释放与状态推进在同一事务内完成，失败整体回滚。
 */
@Service
public class ExpenseService {

    public record SubmitResult(ExpenseReport report, boolean duplicated) {
    }

    private final ExpenseReportRepository reportRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final FxService fxService;
    private final ApprovalPolicyService policyService;
    private final BudgetService budgetService;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;

    public ExpenseService(ExpenseReportRepository reportRepository,
                          ApprovalRecordRepository approvalRecordRepository,
                          FxService fxService,
                          ApprovalPolicyService policyService,
                          BudgetService budgetService,
                          AuditService auditService,
                          org.springframework.transaction.PlatformTransactionManager txManager) {
        this.reportRepository = reportRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.fxService = fxService;
        this.policyService = policyService;
        this.budgetService = budgetService;
        this.auditService = auditService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 幂等提交：同一 idempotencyKey 重复提交返回已有单据（duplicated=true），
     * 不新建单据、不重复占用预算。
     */
    public SubmitResult submit(String idempotencyKey, String applicantId, String department,
                               String expenseType, BigDecimal amount, String currency, Long budgetAccountId) {
        Optional<ExpenseReport> existing = reportRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent() && existing.get().getStatus() != ExpenseStatus.DRAFT) {
            return new SubmitResult(existing.get(), true);
        }

        ExpenseReport report;
        if (existing.isPresent()) {
            // 上次提交在中途失败（单据仍为 DRAFT），继续完成本次提交
            report = existing.get();
        } else {
            // 汇率换算在任何写入之前完成，汇率缺失/过期直接拒绝，不留半成品
            FxService.Conversion conversion = fxService.convert(amount, currency);
            List<String> chain = policyService.resolveChain(department, expenseType, conversion.bookedAmount());
            report = new ExpenseReport(idempotencyKey, applicantId, department, expenseType, amount, currency);
            report.setBookedAmount(conversion.bookedAmount());
            report.setFxRateSnapshot(conversion.rate());
            report.setFxRateDate(conversion.rateDate().toString());
            report.setBudgetAccountId(budgetAccountId);
            report.setSubmitCycle(1);
            for (int i = 0; i < chain.size(); i++) {
                report.getApprovalChain().add(new ApprovalStep(report, i, chain.get(i)));
            }
            try {
                ExpenseReport toSave = report;
                report = txTemplate.execute(tx -> reportRepository.save(toSave));
            } catch (DataIntegrityViolationException e) {
                // 并发重复提交：唯一约束冲突，返回已存在的单据
                ExpenseReport other = reportRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
                return new SubmitResult(other, other.getStatus() != ExpenseStatus.DRAFT);
            }
        }

        // 第一阶段：写入占用意图（独立事务，幂等）
        final ExpenseReport finalReport = report;
        budgetService.prepareHold(finalReport.getId(), finalReport.getBudgetAccountId(),
                finalReport.getBookedAmount(), finalReport.getSubmitCycle());
        // 第二阶段：占用预算与状态推进同事务
        try {
            txTemplate.executeWithoutResult(tx -> {
                budgetService.applyHold(finalReport.getId(), finalReport.getSubmitCycle());
                ExpenseReport locked = reportRepository.findByIdForUpdate(finalReport.getId()).orElseThrow();
                if (locked.getStatus() == ExpenseStatus.DRAFT) {
                    locked.setStatus(ExpenseStatus.SUBMITTED);
                    locked.setCurrentLevel(0);
                    reportRepository.save(locked);
                }
            });
        } catch (BusinessException e) {
            auditService.record(applicantId, "SUBMIT", String.valueOf(finalReport.getId()), "REJECTED",
                    e.getCode() + ": " + e.getMessage());
            throw e;
        }
        auditService.record(applicantId, "SUBMIT", String.valueOf(finalReport.getId()), "SUCCESS",
                "amount=" + amount + " " + currency);
        return new SubmitResult(reportRepository.findById(finalReport.getId()).orElseThrow(), false);
    }

    public ExpenseReport approve(Long reportId, String approverId, String role, String comment) {
        return txTemplate.execute(tx -> {
            ExpenseReport report = lockReport(reportId);
            requireStatus(report, ExpenseStatus.SUBMITTED, "APPROVE");
            requireApproverRole(report, role, approverId, "APPROVE");
            approvalRecordRepository.save(new ApprovalRecord(
                    reportId, report.getCurrentLevel(), approverId, ApprovalAction.APPROVE, comment));
            if (report.getCurrentLevel() < report.getApprovalChain().size() - 1) {
                report.setCurrentLevel(report.getCurrentLevel() + 1);
            } else {
                report.setStatus(ExpenseStatus.APPROVED);
                report.setCurrentLevel(-1);
            }
            ExpenseReport saved = reportRepository.save(report);
            auditService.record(approverId, "APPROVE", String.valueOf(reportId), "SUCCESS",
                    "level=" + report.getCurrentLevel());
            return saved;
        });
    }

    public ExpenseReport reject(Long reportId, String approverId, String role, String comment) {
        budgetService.prepareRelease(reportId, currentCycleOf(reportId), ExpenseStatus.REJECTED.name());
        return txTemplate.execute(tx -> {
            ExpenseReport report = lockReport(reportId);
            requireStatus(report, ExpenseStatus.SUBMITTED, "REJECT");
            requireApproverRole(report, role, approverId, "REJECT");
            approvalRecordRepository.save(new ApprovalRecord(
                    reportId, report.getCurrentLevel(), approverId, ApprovalAction.REJECT, comment));
            budgetService.applyRelease(reportId, report.getSubmitCycle());
            report.setStatus(ExpenseStatus.REJECTED);
            report.setCurrentLevel(-1);
            ExpenseReport saved = reportRepository.save(report);
            auditService.record(approverId, "REJECT", String.valueOf(reportId), "SUCCESS", comment);
            return saved;
        });
    }

    public ExpenseReport withdraw(Long reportId, String operatorId) {
        budgetService.prepareRelease(reportId, currentCycleOf(reportId), ExpenseStatus.WITHDRAWN.name());
        return txTemplate.execute(tx -> {
            ExpenseReport report = lockReport(reportId);
            requireStatus(report, ExpenseStatus.SUBMITTED, "WITHDRAW");
            requireApplicant(report, operatorId, "WITHDRAW");
            budgetService.applyRelease(reportId, report.getSubmitCycle());
            report.setStatus(ExpenseStatus.WITHDRAWN);
            report.setCurrentLevel(-1);
            ExpenseReport saved = reportRepository.save(report);
            auditService.record(operatorId, "WITHDRAW", String.valueOf(reportId), "SUCCESS", null);
            return saved;
        });
    }

    public ExpenseReport resubmit(Long reportId, String operatorId) {
        // 校验并推进提交周期
        ExpenseReport report = txTemplate.execute(tx -> {
            ExpenseReport r = lockReport(reportId);
            if (r.getStatus() != ExpenseStatus.REJECTED && r.getStatus() != ExpenseStatus.WITHDRAWN) {
                auditService.record(operatorId, "RESUBMIT", String.valueOf(reportId), "REJECTED",
                        "illegal transition from " + r.getStatus());
                throw new BusinessException(ErrorCodes.ILLEGAL_STATE_TRANSITION,
                        "cannot resubmit from status " + r.getStatus());
            }
            requireApplicant(r, operatorId, "RESUBMIT");
            r.setSubmitCycle(r.getSubmitCycle() + 1);
            return reportRepository.save(r);
        });
        // 重新占用预算（新周期）并推进状态
        budgetService.prepareHold(report.getId(), report.getBudgetAccountId(),
                report.getBookedAmount(), report.getSubmitCycle());
        ExpenseReport result = txTemplate.execute(tx -> {
            budgetService.applyHold(reportId, report.getSubmitCycle());
            ExpenseReport locked = lockReport(reportId);
            locked.setStatus(ExpenseStatus.SUBMITTED);
            locked.setCurrentLevel(0);
            return reportRepository.save(locked);
        });
        auditService.record(operatorId, "RESUBMIT", String.valueOf(reportId), "SUCCESS",
                "cycle=" + report.getSubmitCycle());
        return result;
    }

    public ExpenseReport get(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "report not found: " + reportId));
    }

    private int currentCycleOf(Long reportId) {
        return get(reportId).getSubmitCycle();
    }

    private ExpenseReport lockReport(Long reportId) {
        return reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "report not found: " + reportId));
    }

    private void requireStatus(ExpenseReport report, ExpenseStatus expected, String action) {
        if (report.getStatus() != expected) {
            throw new BusinessException(ErrorCodes.ILLEGAL_STATE_TRANSITION,
                    "cannot " + action + " report in status " + report.getStatus());
        }
    }

    private void requireApproverRole(ExpenseReport report, String role, String actorId, String action) {
        String required = report.getApprovalChain().get(report.getCurrentLevel()).getApproverRole();
        if (!required.equals(role)) {
            auditService.record(actorId, action, String.valueOf(report.getId()), "DENIED",
                    "required role=" + required + " actual=" + role);
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED,
                    "role " + role + " cannot approve level " + report.getCurrentLevel()
                            + " (required " + required + ")");
        }
    }

    private void requireApplicant(ExpenseReport report, String operatorId, String action) {
        if (!report.getApplicantId().equals(operatorId)) {
            auditService.record(operatorId, action, String.valueOf(report.getId()), "DENIED",
                    "only applicant may " + action);
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED,
                    "only applicant may " + action + " this report");
        }
    }
}
