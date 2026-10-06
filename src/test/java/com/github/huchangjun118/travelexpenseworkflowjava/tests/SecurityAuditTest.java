package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditLog;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.AuditLogRepository;
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
class SecurityAuditTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;
    @Autowired
    BudgetIntentRepository intentRepository;
    @Autowired
    AuditLogRepository auditLogRepository;

    Long budgetId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        intentRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        auditLogRepository.deleteAll();
        budgetId = budgetAccountRepository.save(new BudgetAccount("IT-2026", new BigDecimal("100000"))).getId();
    }

    private Long submit(String key) {
        return expenseService.submit(key, "alice", "IT", "TRAVEL",
                new BigDecimal("500"), "CNY", budgetId).report().getId();
    }

    @Test
    void nonApplicantCannotWithdrawAndAttemptIsAudited() {
        Long id = submit("sec-1");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> expenseService.withdraw(id, "mallory"));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
        assertEquals(ExpenseStatus.SUBMITTED, expenseService.get(id).getStatus());

        List<AuditLog> logs = auditLogRepository.findByResourceIdOrderByCreatedAtAsc(String.valueOf(id));
        assertTrue(logs.stream().anyMatch(l ->
                l.getAction().equals("WITHDRAW") && l.getResult().equals("DENIED")
                        && l.getActorId().equals("mallory")),
                "denied attempt must be audited");
    }

    @Test
    void nonApplicantCannotResubmit() {
        Long id = submit("sec-2");
        expenseService.withdraw(id, "alice");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> expenseService.resubmit(id, "mallory"));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
        assertEquals(ExpenseStatus.WITHDRAWN, expenseService.get(id).getStatus());
    }

    @Test
    void wrongRoleApprovalIsDeniedAndAudited() {
        Long id = submit("sec-3");
        assertThrows(BusinessException.class,
                () -> expenseService.approve(id, "mallory", "INTERN", null));
        List<AuditLog> logs = auditLogRepository.findByResourceIdOrderByCreatedAtAsc(String.valueOf(id));
        assertTrue(logs.stream().anyMatch(l ->
                l.getAction().equals("APPROVE") && l.getResult().equals("DENIED")));
    }

    @Test
    void keyOperationsAreAuditedInOrder() {
        Long id = submit("sec-4");
        expenseService.approve(id, "mgr", "MANAGER", "ok");

        List<AuditLog> logs = auditLogRepository.findByResourceIdOrderByCreatedAtAsc(String.valueOf(id));
        assertTrue(logs.size() >= 2);
        assertEquals("SUBMIT", logs.get(0).getAction());
        assertEquals("SUCCESS", logs.get(0).getResult());
        assertEquals("APPROVE", logs.get(1).getAction());
        // 审计 id 单调递增（只追加）
        for (int i = 1; i < logs.size(); i++) {
            assertTrue(logs.get(i).getId() > logs.get(i - 1).getId());
        }
    }

    @Test
    void deniedAuditSurvivesBusinessRollback() {
        Long id = submit("sec-5");
        // 越权撤回触发业务回滚，但 DENIED 审计必须留存
        assertThrows(BusinessException.class, () -> expenseService.withdraw(id, "mallory"));
        List<AuditLog> logs = auditLogRepository.findByResourceIdOrderByCreatedAtAsc(String.valueOf(id));
        assertTrue(logs.stream().anyMatch(l -> l.getResult().equals("DENIED")));
        // 预算未被释放
        assertEquals(0, new BigDecimal("500.0000").compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
    }
}
