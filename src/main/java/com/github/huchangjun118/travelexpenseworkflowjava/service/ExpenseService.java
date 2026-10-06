package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalStep;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.Decision;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseType;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.HoldState;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.Money;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.RateSnapshot;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import com.github.huchangjun118.travelexpenseworkflowjava.policy.ApprovalPolicy;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.ExpenseReportStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.IdempotencyStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报销单核心服务。
 * 状态机：DRAFT -> SUBMITTED -> APPROVED / REJECTED / WITHDRAWN，
 * REJECTED / WITHDRAWN -> SUBMITTED（重新提交）。其余流转一律拒绝。
 */
@Service
public class ExpenseService {
    private static final String ADMIN_ROLE = "ADMIN";

    private final ExpenseReportStore reportStore;
    private final IdempotencyStore idempotencyStore;
    private final BudgetAccountStore budgetAccountStore;
    private final ApprovalPolicy approvalPolicy;
    private final ExchangeRateService exchangeRateService;
    private final BudgetService budgetService;
    private final AuditService auditService;
    private final ConcurrentHashMap<String, Object> submitLocks = new ConcurrentHashMap<>();

    public ExpenseService(ExpenseReportStore reportStore, IdempotencyStore idempotencyStore,
                          BudgetAccountStore budgetAccountStore, ApprovalPolicy approvalPolicy,
                          ExchangeRateService exchangeRateService, BudgetService budgetService,
                          AuditService auditService) {
        this.reportStore = reportStore;
        this.idempotencyStore = idempotencyStore;
        this.budgetAccountStore = budgetAccountStore;
        this.approvalPolicy = approvalPolicy;
        this.exchangeRateService = exchangeRateService;
        this.budgetService = budgetService;
        this.auditService = auditService;
    }

    /** 创建草稿。 */
    public ExpenseReport createDraft(String userId, Money amount, ExpenseType expenseType,
                                     String department, String description) {
        requireAuthenticated(userId);
        validatePayload(amount, expenseType, department);
        ExpenseReport report = newReport(userId, amount, expenseType, department, description);
        reportStore.save(report);
        auditService.record(userId, "CREATE_DRAFT", report.getId(), AuditService.RESULT_OK, null);
        return report;
    }

    /** 提交草稿：DRAFT -> SUBMITTED，幂等（重试命中幂等键时直接返回原结果）。 */
    public SubmitResult submitDraft(String userId, String idempotencyKey, String draftId) {
        requireAuthenticated(userId);
        var duplicate = findDuplicate(userId, idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        ExpenseReport draft = getOrThrow(draftId);
        synchronized (draft) {
            if (draft.getStatus() != ExpenseStatus.DRAFT) {
                throw transitionError(draft, "SUBMIT");
            }
            if (!draft.getApplicantId().equals(userId)) {
                throw denied(userId, "SUBMIT", draftId, "只有申请人可以提交草稿");
            }
        }
        return doSubmit(userId, idempotencyKey, draft);
    }

    /** 新建并提交（一步式），同一幂等键只产生一份有效单据、只占用一次预算。 */
    public SubmitResult submit(String userId, String idempotencyKey, Money amount,
                               ExpenseType expenseType, String department, String description) {
        requireAuthenticated(userId);
        validatePayload(amount, expenseType, department);
        ExpenseReport draft = newReport(userId, amount, expenseType, department, description);
        return doSubmit(userId, idempotencyKey, draft);
    }

    private SubmitResult doSubmit(String userId, String idempotencyKey, ExpenseReport draft) {
        Object lock = submitLocks.computeIfAbsent(idempotencyKey, k -> new Object());
        synchronized (lock) {
            var existingId = idempotencyStore.findReportId(idempotencyKey);
            if (existingId.isPresent()) {
                ExpenseReport existing = getOrThrow(existingId.get());
                auditService.record(userId, "SUBMIT", existing.getId(), AuditService.RESULT_OK,
                        "重复提交，命中幂等键: " + idempotencyKey);
                return new SubmitResult(existing, true);
            }
            try {
                activate(userId, draft);
            } catch (BusinessException e) {
                auditService.record(userId, "SUBMIT", draft.getId(), AuditService.RESULT_REJECTED,
                        e.getCode() + ": " + e.getMessage());
                throw e;
            }
            reportStore.save(draft);
            idempotencyStore.putIfAbsent(idempotencyKey, draft.getId());
            auditService.record(userId, "SUBMIT", draft.getId(), AuditService.RESULT_OK, null);
            return new SubmitResult(draft, false);
        }
    }

    /** 命中幂等键时返回原提交结果（duplicate=true），否则返回 null。 */
    private SubmitResult findDuplicate(String userId, String idempotencyKey) {
        return idempotencyStore.findReportId(idempotencyKey)
                .map(id -> {
                    ExpenseReport existing = getOrThrow(id);
                    auditService.record(userId, "SUBMIT", id, AuditService.RESULT_OK,
                            "重复提交，命中幂等键: " + idempotencyKey);
                    return new SubmitResult(existing, true);
                })
                .orElse(null);
    }

    /** DRAFT/REJECTED/WITHDRAWN -> SUBMITTED：固化审批链与汇率快照，占用预算。 */
    private void activate(String userId, ExpenseReport report) {
        synchronized (report) {
            RateSnapshot snapshot = exchangeRateService.toLedger(report.getAmount());
            List<ApprovalStep> chain = approvalPolicy.resolveChain(snapshot.getLedgerAmount(),
                    report.getExpenseType().name(), report.getDepartment());
            BudgetAccount account = budgetAccountStore.findByDepartment(report.getDepartment())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUDGET_NOT_FOUND,
                            "部门无预算账户: " + report.getDepartment()));
            budgetService.hold(report.getId(), account.getId(), snapshot.getLedgerAmount());
            report.setRateSnapshot(snapshot);
            report.setApprovalChain(chain);
            report.setCurrentStepIndex(0);
            report.setBudgetAccountId(account.getId());
            report.setHoldState(HoldState.HELD);
            report.setStatus(ExpenseStatus.SUBMITTED);
            touch(report);
        }
    }

    /** 审批通过当前层级；最后一级通过后单据 APPROVED 并核销预算。 */
    public ExpenseReport approve(String userId, String role, String reportId, String comment) {
        requireAuthenticated(userId);
        ExpenseReport report = getOrThrow(reportId);
        synchronized (report) {
            if (report.getStatus() != ExpenseStatus.SUBMITTED) {
                throw transitionError(report, "APPROVE");
            }
            ApprovalStep step = report.getApprovalChain().get(report.getCurrentStepIndex());
            requireApproverRole(userId, role, reportId, step);
            step.setDecision(Decision.APPROVED);
            step.setDecidedBy(userId);
            step.setComment(comment);
            if (report.getCurrentStepIndex() == report.getApprovalChain().size() - 1) {
                budgetService.consume(reportId, report.getBudgetAccountId(),
                        report.getRateSnapshot().getLedgerAmount());
                report.setHoldState(HoldState.CONSUMED);
                report.setStatus(ExpenseStatus.APPROVED);
            } else {
                report.setCurrentStepIndex(report.getCurrentStepIndex() + 1);
            }
            touch(report);
        }
        auditService.record(userId, "APPROVE", reportId, AuditService.RESULT_OK, comment);
        return report;
    }

    /** 驳回：单据 REJECTED 并释放预算（只释放一次）。 */
    public ExpenseReport reject(String userId, String role, String reportId, String comment) {
        requireAuthenticated(userId);
        ExpenseReport report = getOrThrow(reportId);
        synchronized (report) {
            if (report.getStatus() != ExpenseStatus.SUBMITTED) {
                throw transitionError(report, "REJECT");
            }
            ApprovalStep step = report.getApprovalChain().get(report.getCurrentStepIndex());
            requireApproverRole(userId, role, reportId, step);
            step.setDecision(Decision.REJECTED);
            step.setDecidedBy(userId);
            step.setComment(comment);
            releaseHoldOnce(report);
            report.setStatus(ExpenseStatus.REJECTED);
            touch(report);
        }
        auditService.record(userId, "REJECT", reportId, AuditService.RESULT_OK, comment);
        return report;
    }

    /** 撤回：仅申请人，SUBMITTED -> WITHDRAWN，释放预算（只释放一次）。 */
    public ExpenseReport withdraw(String userId, String reportId) {
        requireAuthenticated(userId);
        ExpenseReport report = getOrThrow(reportId);
        synchronized (report) {
            if (report.getStatus() != ExpenseStatus.SUBMITTED) {
                throw transitionError(report, "WITHDRAW");
            }
            if (!report.getApplicantId().equals(userId)) {
                throw denied(userId, "WITHDRAW", reportId, "只有申请人可以撤回");
            }
            releaseHoldOnce(report);
            report.setStatus(ExpenseStatus.WITHDRAWN);
            touch(report);
        }
        auditService.record(userId, "WITHDRAW", reportId, AuditService.RESULT_OK, null);
        return report;
    }

    /** 重新提交：REJECTED / WITHDRAWN -> SUBMITTED，重新固化审批链与汇率快照并重新占用预算。 */
    public SubmitResult resubmit(String userId, String idempotencyKey, String reportId) {
        requireAuthenticated(userId);
        var duplicate = findDuplicate(userId, idempotencyKey);
        if (duplicate != null) {
            return duplicate;
        }
        ExpenseReport report = getOrThrow(reportId);
        synchronized (report) {
            if (report.getStatus() != ExpenseStatus.REJECTED
                    && report.getStatus() != ExpenseStatus.WITHDRAWN) {
                throw transitionError(report, "RESUBMIT");
            }
            if (!report.getApplicantId().equals(userId)) {
                throw denied(userId, "RESUBMIT", reportId, "只有申请人可以重新提交");
            }
        }
        return doSubmit(userId, idempotencyKey, report);
    }

    public ExpenseReport get(String reportId) {
        return getOrThrow(reportId);
    }

    /** 释放占用，保证同一单据只释放一次。 */
    private void releaseHoldOnce(ExpenseReport report) {
        if (report.getHoldState() == HoldState.HELD) {
            budgetService.release(report.getId(), report.getBudgetAccountId(),
                    report.getRateSnapshot().getLedgerAmount());
            report.setHoldState(HoldState.RELEASED);
        }
    }

    private void requireApproverRole(String userId, String role, String reportId, ApprovalStep step) {
        if (!ADMIN_ROLE.equals(role) && !step.getRole().equals(role)) {
            throw denied(userId, "DECIDE", reportId,
                    "角色 " + role + " 无权审批当前层级（需要 " + step.getRole() + "）");
        }
    }

    private BusinessException denied(String userId, String action, String reportId, String reason) {
        auditService.record(userId, action, reportId, AuditService.RESULT_DENIED, reason);
        return new BusinessException(ErrorCode.PERMISSION_DENIED, reason);
    }

    private BusinessException transitionError(ExpenseReport report, String action) {
        return new BusinessException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                "非法状态流转: " + action + " @ " + report.getStatus() + ", report=" + report.getId());
    }

    private ExpenseReport getOrThrow(String reportId) {
        return reportStore.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND,
                        "报销单不存在: " + reportId));
    }

    private void requireAuthenticated(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "未认证用户");
        }
    }

    private void validatePayload(Money amount, ExpenseType expenseType, String department) {
        if (amount == null || amount.getAmount() == null
                || amount.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "金额必须为正数");
        }
        if (expenseType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "费用类型不能为空");
        }
        if (department == null || department.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "部门不能为空");
        }
    }

    private ExpenseReport newReport(String userId, Money amount, ExpenseType expenseType,
                                    String department, String description) {
        ExpenseReport report = new ExpenseReport();
        report.setId(UUID.randomUUID().toString());
        report.setApplicantId(userId);
        report.setAmount(amount);
        report.setExpenseType(expenseType);
        report.setDepartment(department);
        report.setDescription(description);
        report.setCreatedAt(Instant.now());
        report.setUpdatedAt(report.getCreatedAt());
        return report;
    }

    private void touch(ExpenseReport report) {
        report.setUpdatedAt(Instant.now());
        report.setVersion(report.getVersion() + 1);
    }

    /** 提交结果：duplicate=true 表示命中幂等键的重复提交。 */
    public record SubmitResult(ExpenseReport report, boolean duplicate) {
    }
}
