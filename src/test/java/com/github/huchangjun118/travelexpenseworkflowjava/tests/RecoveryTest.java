package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetIntent;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentType;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetIntentRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BudgetRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟进程在"写入 PENDING 意图"与"应用预算变动"之间崩溃，
 * 验证重启后恢复流程按策略继续完成或整体回滚。
 */
@SpringBootTest
class RecoveryTest {

    @Autowired
    BudgetRecoveryService recoveryService;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;
    @Autowired
    BudgetIntentRepository intentRepository;
    @Autowired
    ExpenseProperties properties;

    Long budgetId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        intentRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        budgetId = budgetAccountRepository.save(new BudgetAccount("IT-2026", new BigDecimal("10000"))).getId();
    }

    /** 崩溃现场：单据已建（DRAFT），HOLD 意图已写入但未应用。 */
    private ExpenseReport crashAfterPrepareHold(String key, String amount) {
        ExpenseReport report = new ExpenseReport(key, "alice", "IT", "TRAVEL",
                new BigDecimal(amount), "CNY");
        report.setBookedAmount(new BigDecimal(amount));
        report.setBudgetAccountId(budgetId);
        report.setSubmitCycle(1);
        report = reportRepository.save(report);
        intentRepository.save(new BudgetIntent(report.getId(), budgetId, new BigDecimal(amount),
                IntentType.HOLD, 1, ExpenseStatus.SUBMITTED.name()));
        return report;
    }

    @Test
    void commitStrategyCompletesPendingHold() {
        ExpenseReport report = crashAfterPrepareHold("rec-1", "500");
        properties.setRecoveryStrategy("COMMIT");

        int processed = recoveryService.recover();
        assertEquals(1, processed);

        BudgetIntent intent = intentRepository.findAll().get(0);
        assertEquals(IntentStatus.COMMITTED, intent.getStatus());
        assertEquals(0, new BigDecimal("500.0000").compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
        assertEquals(ExpenseStatus.SUBMITTED,
                reportRepository.findById(report.getId()).orElseThrow().getStatus());
    }

    @Test
    void rollbackStrategyDiscardsPendingHold() {
        ExpenseReport report = crashAfterPrepareHold("rec-2", "500");
        properties.setRecoveryStrategy("ROLLBACK");
        try {
            int processed = recoveryService.recover();
            assertEquals(1, processed);

            BudgetIntent intent = intentRepository.findAll().get(0);
            assertEquals(IntentStatus.ROLLED_BACK, intent.getStatus());
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
            // 单据保持 DRAFT，可由客户端重试提交
            assertEquals(ExpenseStatus.DRAFT,
                    reportRepository.findById(report.getId()).orElseThrow().getStatus());
        } finally {
            properties.setRecoveryStrategy("COMMIT");
        }
    }

    @Test
    void commitStrategyCompletesPendingRelease() {
        // 崩溃现场：单据已提交且预算已占用，撤回时 RELEASE 意图已写入但未应用
        ExpenseReport report = new ExpenseReport("rec-3", "alice", "IT", "TRAVEL",
                new BigDecimal("500"), "CNY");
        report.setBookedAmount(new BigDecimal("500"));
        report.setBudgetAccountId(budgetId);
        report.setSubmitCycle(1);
        report.setStatus(ExpenseStatus.SUBMITTED);
        report.setCurrentLevel(0);
        report = reportRepository.save(report);
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        account.setUsedAmount(new BigDecimal("500"));
        budgetAccountRepository.save(account);
        intentRepository.save(new BudgetIntent(report.getId(), budgetId, new BigDecimal("500"),
                IntentType.HOLD, 1, ExpenseStatus.SUBMITTED.name()));
        BudgetIntent hold = intentRepository.findAll().get(0);
        hold.setStatus(IntentStatus.COMMITTED);
        intentRepository.save(hold);
        intentRepository.save(new BudgetIntent(report.getId(), budgetId, new BigDecimal("500"),
                IntentType.RELEASE, 1, ExpenseStatus.WITHDRAWN.name()));

        properties.setRecoveryStrategy("COMMIT");
        recoveryService.recover();

        assertEquals(0, BigDecimal.ZERO.compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
        assertEquals(ExpenseStatus.WITHDRAWN,
                reportRepository.findById(report.getId()).orElseThrow().getStatus());
        long committed = intentRepository.findAll().stream()
                .filter(i -> i.getStatus() == IntentStatus.COMMITTED).count();
        assertEquals(2, committed);
    }

    @Test
    void recoveryIsIdempotent() {
        crashAfterPrepareHold("rec-4", "500");
        properties.setRecoveryStrategy("COMMIT");
        recoveryService.recover();
        // 再次执行恢复不应重复占用
        int second = recoveryService.recover();
        assertEquals(0, second);
        assertEquals(0, new BigDecimal("500.0000").compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
    }
}
