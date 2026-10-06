package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalAction;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalRecord;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ApprovalRecordRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetIntentRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ErrorCodes;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StateMachineTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    ApprovalRecordRepository approvalRecordRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;
    @Autowired
    BudgetIntentRepository intentRepository;

    Long budgetId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        intentRepository.deleteAll();
        approvalRecordRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        budgetId = budgetAccountRepository.save(new BudgetAccount("IT-2026", new BigDecimal("100000"))).getId();
    }

    private Long submitReport(String key, String amount) {
        return expenseService.submit(key, "alice", "IT", "TRAVEL",
                new BigDecimal(amount), "CNY", budgetId).report().getId();
    }

    @Test
    void multiLevelApprovalAdvancesLevelByLevel() {
        // 5000 CNY -> MANAGER + DIRECTOR 两级
        Long id = submitReport("sm-1", "5000");
        ExpenseReport report = expenseService.get(id);
        assertEquals(ExpenseStatus.SUBMITTED, report.getStatus());
        assertEquals(0, report.getCurrentLevel());

        expenseService.approve(id, "mgr1", "MANAGER", "ok");
        report = expenseService.get(id);
        assertEquals(ExpenseStatus.SUBMITTED, report.getStatus());
        assertEquals(1, report.getCurrentLevel());

        expenseService.approve(id, "dir1", "DIRECTOR", "ok");
        report = expenseService.get(id);
        assertEquals(ExpenseStatus.APPROVED, report.getStatus());
        assertEquals(-1, report.getCurrentLevel());

        // 审批记录与状态推进一致
        List<ApprovalRecord> records = approvalRecordRepository.findByReportIdOrderByCreatedAtAsc(id);
        assertEquals(2, records.size());
        assertEquals(0, records.get(0).getLevelIndex());
        assertEquals(ApprovalAction.APPROVE, records.get(0).getAction());
        assertEquals(1, records.get(1).getLevelIndex());
    }

    @Test
    void illegalTransitionsAreRejected() {
        Long id = submitReport("sm-2", "500");

        // 已审批通过的单据不能再审批/驳回/撤回/重新提交
        expenseService.approve(id, "mgr1", "MANAGER", "ok");
        assertEquals(ExpenseStatus.APPROVED, expenseService.get(id).getStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> expenseService.approve(id, "mgr1", "MANAGER", null));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());
        ex = assertThrows(BusinessException.class,
                () -> expenseService.reject(id, "mgr1", "MANAGER", null));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());
        ex = assertThrows(BusinessException.class,
                () -> expenseService.withdraw(id, "alice"));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());
        ex = assertThrows(BusinessException.class,
                () -> expenseService.resubmit(id, "alice"));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());
    }

    @Test
    void withdrawOnlyFromSubmittedAndResubmitOnlyFromTerminal() {
        Long id = submitReport("sm-3", "500");
        // 提交中不能重复提交流转（resubmit 非法）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> expenseService.resubmit(id, "alice"));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());

        expenseService.withdraw(id, "alice");
        assertEquals(ExpenseStatus.WITHDRAWN, expenseService.get(id).getStatus());

        // 撤回后不能审批
        ex = assertThrows(BusinessException.class,
                () -> expenseService.approve(id, "mgr1", "MANAGER", null));
        assertEquals(ErrorCodes.ILLEGAL_STATE_TRANSITION, ex.getCode());

        // 撤回后可重新提交
        expenseService.resubmit(id, "alice");
        assertEquals(ExpenseStatus.SUBMITTED, expenseService.get(id).getStatus());
    }

    @Test
    void rejectReleasesBudgetAndAllowsResubmit() {
        Long id = submitReport("sm-4", "500");
        expenseService.reject(id, "mgr1", "MANAGER", "budget cut");
        ExpenseReport report = expenseService.get(id);
        assertEquals(ExpenseStatus.REJECTED, report.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));

        List<ApprovalRecord> records = approvalRecordRepository.findByReportIdOrderByCreatedAtAsc(id);
        assertEquals(1, records.size());
        assertEquals(ApprovalAction.REJECT, records.get(0).getAction());

        // 驳回后重新提交，预算重新占用，审批链从头开始
        expenseService.resubmit(id, "alice");
        report = expenseService.get(id);
        assertEquals(ExpenseStatus.SUBMITTED, report.getStatus());
        assertEquals(0, report.getCurrentLevel());
        assertEquals(0, new BigDecimal("500.0000").compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
    }

    @Test
    void wrongApproverRoleCannotAdvanceChain() {
        Long id = submitReport("sm-5", "5000");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> expenseService.approve(id, "dir1", "DIRECTOR", null));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
        // 状态未被推进
        ExpenseReport report = expenseService.get(id);
        assertEquals(ExpenseStatus.SUBMITTED, report.getStatus());
        assertEquals(0, report.getCurrentLevel());
        assertTrue(approvalRecordRepository.findByReportIdOrderByCreatedAtAsc(id).isEmpty());
    }
}
