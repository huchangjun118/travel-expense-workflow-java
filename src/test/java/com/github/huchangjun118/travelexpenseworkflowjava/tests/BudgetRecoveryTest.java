package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalEntry;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalType;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.JournalStore;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障恢复：进程在写日志与状态推进之间"崩溃"后，
 * 重启（以同一存储重建 BudgetService 模拟）能识别 PENDING 日志并按策略处理。
 */
class BudgetRecoveryTest {

    private ExpenseProperties properties;
    private BudgetAccountStore accountStore;
    private JournalStore journalStore;
    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        properties = new ExpenseProperties();
        properties.setRecoveryStrategy("COMPLETE");
        accountStore = new BudgetAccountStore();
        accountStore.save(new BudgetAccount("acc", "ENG", new BigDecimal("1000")));
        journalStore = new JournalStore();
        budgetService = new BudgetService(accountStore, journalStore, properties);
    }

    private BudgetAccount account() {
        return accountStore.findById("acc").orElseThrow();
    }

    /** 模拟重启：同一批存储 + 新的服务实例。 */
    private BudgetService restart() {
        return new BudgetService(accountStore, journalStore, properties);
    }

    @Test
    void completeStrategyFinishesPendingHold() {
        // 崩溃点：PENDING 日志已写入，余额尚未变更
        journalStore.save(new JournalEntry("j1", "r1", "acc", new BigDecimal("100"), JournalType.HOLD));

        restart().recoverPendingJournals();

        assertEquals(0, new BigDecimal("100").compareTo(account().getHeld()));
        assertEquals(JournalStatus.DONE, journalStore.findByStatus(JournalStatus.DONE).get(0).getStatus());
        assertTrue(journalStore.findByStatus(JournalStatus.PENDING).isEmpty());
    }

    @Test
    void completeStrategyIsIdempotentWhenHoldWasAlreadyApplied() {
        // 崩溃点：余额已变更，但日志仍停留 PENDING（未来得及置 DONE）
        budgetService.hold("r1", "acc", new BigDecimal("100"));
        JournalEntry entry = journalStore.findByStatus(JournalStatus.DONE).get(0);
        entry.setStatus(JournalStatus.PENDING); // 模拟崩溃留下的半成品状态

        restart().recoverPendingJournals();

        // 重放幂等：占用不会被加两次
        assertEquals(0, new BigDecimal("100").compareTo(account().getHeld()));
        assertEquals(JournalStatus.DONE, entry.getStatus());
    }

    @Test
    void rollbackStrategyDiscardsUnappliedPending() {
        properties.setRecoveryStrategy("ROLLBACK");
        journalStore.save(new JournalEntry("j1", "r1", "acc", new BigDecimal("100"), JournalType.HOLD));

        restart().recoverPendingJournals();

        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));
        assertEquals(1, journalStore.findByStatus(JournalStatus.ROLLED_BACK).size());
    }

    @Test
    void rollbackStrategyCompensatesAppliedPending() {
        properties.setRecoveryStrategy("ROLLBACK");
        budgetService.hold("r1", "acc", new BigDecimal("100"));
        JournalEntry entry = journalStore.findByStatus(JournalStatus.DONE).get(0);
        entry.setStatus(JournalStatus.PENDING); // 已应用但未收尾 -> 整体回滚需补偿

        restart().recoverPendingJournals();

        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));
        assertEquals(JournalStatus.ROLLED_BACK, entry.getStatus());
    }

    @Test
    void noPartialWriteWhenHoldFails() {
        // 超额占用：日志落为 ROLLED_BACK，余额不变，不留下部分写入
        assertThrows(Exception.class,
                () -> budgetService.hold("r1", "acc", new BigDecimal("2000")));
        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));
        assertEquals(1, journalStore.findByStatus(JournalStatus.ROLLED_BACK).size());
        assertTrue(journalStore.findByStatus(JournalStatus.PENDING).isEmpty());
    }
}
