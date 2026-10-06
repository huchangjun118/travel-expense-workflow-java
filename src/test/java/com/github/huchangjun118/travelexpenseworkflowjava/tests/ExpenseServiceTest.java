package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseType;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.Money;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import com.github.huchangjun118.travelexpenseworkflowjava.policy.ApprovalPolicy;
import com.github.huchangjun118.travelexpenseworkflowjava.service.AuditService;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BudgetService;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExchangeRateService;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExpenseService;
import com.github.huchangjun118.travelexpenseworkflowjava.store.AuditStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.ExpenseReportStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.IdempotencyStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.JournalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseServiceTest {

    private ExpenseProperties properties;
    private BudgetAccountStore accountStore;
    private ExchangeRateService exchangeRateService;
    private ExpenseService expenseService;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        properties = new ExpenseProperties();
        properties.setLedgerCurrency("CNY");
        properties.setRateMaxAgeSeconds(3600);
        ExpenseProperties.PolicyRule small = new ExpenseProperties.PolicyRule();
        small.setMaxAmount(new BigDecimal("500"));
        small.setApproverRoles(List.of("MANAGER"));
        ExpenseProperties.PolicyRule large = new ExpenseProperties.PolicyRule();
        large.setApproverRoles(List.of("MANAGER", "FINANCE"));
        properties.setPolicyRules(new ArrayList<>(List.of(small, large)));

        accountStore = new BudgetAccountStore();
        accountStore.save(new BudgetAccount("acc-eng", "ENG", new BigDecimal("1000")));
        exchangeRateService = new ExchangeRateService(properties);
        exchangeRateService.putRate("USD", "CNY", new BigDecimal("7.0"), Instant.now());
        auditService = new AuditService(new AuditStore());
        BudgetService budgetService = new BudgetService(accountStore, new JournalStore(), properties);
        expenseService = new ExpenseService(new ExpenseReportStore(), new IdempotencyStore(),
                accountStore, new ApprovalPolicy(properties), exchangeRateService,
                budgetService, auditService);
    }

    private ExpenseService.SubmitResult submit(String user, String key, String amount, String currency) {
        return expenseService.submit(user, key, new Money(new BigDecimal(amount), currency),
                ExpenseType.TRAVEL, "ENG", "出差");
    }

    private BudgetAccount account() {
        return accountStore.findById("acc-eng").orElseThrow();
    }

    // ---------- 1. 幂等提交 ----------

    @Test
    void duplicateSubmitReturnsSameReportAndHoldsBudgetOnce() {
        var first = submit("alice", "key-1", "100", "CNY");
        var second = submit("alice", "key-1", "100", "CNY");

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.report().getId(), second.report().getId());
        // 预算只占用一次
        assertEquals(0, new BigDecimal("100").compareTo(account().getHeld()));
    }

    @Test
    void concurrentDuplicateSubmitCreatesOnlyOneReport() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        var results = new ConcurrentLinkedQueue<ExpenseService.SubmitResult>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(submit("alice", "key-race", "100", "CNY"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads, results.size());
        assertEquals(1, results.stream().filter(r -> !r.duplicate()).count());
        assertEquals(1, results.stream().map(r -> r.report().getId()).distinct().count());
        assertEquals(0, new BigDecimal("100").compareTo(account().getHeld()));
    }

    // ---------- 2. 并发预算占用 ----------

    @Test
    void concurrentSubmitsNeverOverAllocate() throws InterruptedException {
        int threads = 10; // 每笔 300，总额 1000，最多成功 3 笔
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        var errors = new ConcurrentLinkedQueue<BusinessException>();
        var ok = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            String key = "k-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ok.add(submit("alice", key, "300", "CNY"));
                } catch (BusinessException e) {
                    errors.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(3, ok.size());
        assertEquals(7, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.getCode() == ErrorCode.BUDGET_EXCEEDED));
        assertEquals(0, new BigDecimal("900").compareTo(account().getHeld()));
    }

    @Test
    void concurrentApproveAndWithdrawExactlyOneWins() throws InterruptedException {
        var submitted = submit("alice", "key-aw", "100", "CNY");
        String reportId = submitted.report().getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        var outcomes = new ConcurrentLinkedQueue<String>();
        pool.submit(() -> {
            try {
                start.await();
                expenseService.approve("mgr", "MANAGER", reportId, "ok");
                outcomes.add("APPROVED");
            } catch (BusinessException e) {
                outcomes.add("APPROVE_" + e.getCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                expenseService.withdraw("alice", reportId);
                outcomes.add("WITHDRAWN");
            } catch (BusinessException e) {
                outcomes.add("WITHDRAW_" + e.getCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.contains("APPROVE_" + ErrorCode.ILLEGAL_STATE_TRANSITION)
                || outcomes.contains("WITHDRAW_" + ErrorCode.ILLEGAL_STATE_TRANSITION));
        ExpenseStatus finalStatus = expenseService.get(reportId).getStatus();
        assertTrue(finalStatus == ExpenseStatus.APPROVED || finalStatus == ExpenseStatus.WITHDRAWN);
        // 预算一致性：占用要么被核销要么被释放，绝不重复
        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));
        if (finalStatus == ExpenseStatus.APPROVED) {
            assertEquals(0, new BigDecimal("100").compareTo(account().getConsumed()));
        } else {
            assertEquals(0, BigDecimal.ZERO.compareTo(account().getConsumed()));
        }
    }

    // ---------- 3. 状态机非法流转 ----------

    @Test
    void illegalTransitionsAreRejected() {
        // 草稿不能审批
        ExpenseReport draft = expenseService.createDraft("alice",
                new Money(new BigDecimal("100"), "CNY"), ExpenseType.TRAVEL, "ENG", "d");
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> expenseService.approve("mgr", "MANAGER", draft.getId(), null));
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, e1.getCode());

        // 已驳回不能撤回
        var submitted = submit("alice", "key-it", "100", "CNY");
        String id = submitted.report().getId();
        expenseService.reject("mgr", "MANAGER", id, "no");
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> expenseService.withdraw("alice", id));
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, e2.getCode());

        // 已审批通过不能重复审批
        var submitted2 = submit("alice", "key-it2", "100", "CNY");
        String id2 = submitted2.report().getId();
        expenseService.approve("mgr", "MANAGER", id2, "ok");
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> expenseService.approve("mgr", "MANAGER", id2, "again"));
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, e3.getCode());
    }

    @Test
    void rejectReleasesBudgetExactlyOnceAndResubmitReHolds() {
        var submitted = submit("alice", "key-rej", "100", "CNY");
        String id = submitted.report().getId();
        expenseService.reject("mgr", "MANAGER", id, "no");
        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));

        // 重复驳回被拒绝，不会二次释放
        assertThrows(BusinessException.class,
                () -> expenseService.reject("mgr", "MANAGER", id, "again"));
        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));

        // 重新提交后重新占用，并可审批通过
        var resubmitted = expenseService.resubmit("alice", "key-rej-2", id);
        assertFalse(resubmitted.duplicate());
        assertEquals(ExpenseStatus.SUBMITTED, resubmitted.report().getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(account().getHeld()));
        expenseService.approve("mgr", "MANAGER", id, "ok");
        assertEquals(ExpenseStatus.APPROVED, expenseService.get(id).getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(account().getConsumed()));
    }

    // ---------- 4. 多币种与汇率快照 ----------

    @Test
    void foreignCurrencyUsesRateSnapshotAndIsTraceable() {
        var result = submit("alice", "key-fx", "10", "USD");
        ExpenseReport report = result.report();
        assertNotNull(report.getRateSnapshot());
        assertEquals("USD", report.getRateSnapshot().getFromCurrency());
        assertEquals("CNY", report.getRateSnapshot().getToCurrency());
        assertEquals(0, new BigDecimal("7.0").compareTo(report.getRateSnapshot().getRate()));
        assertEquals(0, new BigDecimal("70.00").compareTo(report.getRateSnapshot().getLedgerAmount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(account().getHeld()));
    }

    @Test
    void missingOrExpiredRateRejectsSubmission() {
        BusinessException missing = assertThrows(BusinessException.class,
                () -> submit("alice", "key-eur", "10", "EUR"));
        assertEquals(ErrorCode.RATE_MISSING, missing.getCode());

        exchangeRateService.putRate("JPY", "CNY", new BigDecimal("0.05"),
                Instant.now().minusSeconds(7200));
        BusinessException expired = assertThrows(BusinessException.class,
                () -> submit("alice", "key-jpy", "1000", "JPY"));
        assertEquals(ErrorCode.RATE_EXPIRED, expired.getCode());
        // 拒绝入账，未占用预算
        assertEquals(0, BigDecimal.ZERO.compareTo(account().getHeld()));
    }

    // ---------- 5. 审批链固化 ----------

    @Test
    void approvalChainFrozenAtSubmitTime() {
        var result = submit("alice", "key-freeze", "100", "CNY");
        String id = result.report().getId();
        assertEquals(List.of("MANAGER"),
                result.report().getApprovalChain().stream().map(s -> s.getRole()).toList());

        // 提交后变更策略：所有单据都需要 CFO
        ExpenseProperties.PolicyRule strict = new ExpenseProperties.PolicyRule();
        strict.setApproverRoles(List.of("CFO"));
        properties.setPolicyRules(new ArrayList<>(List.of(strict)));

        // 在途单据仍按旧链审批，MANAGER 可通过
        expenseService.approve("mgr", "MANAGER", id, "ok");
        assertEquals(ExpenseStatus.APPROVED, expenseService.get(id).getStatus());
        assertEquals(List.of("MANAGER"),
                expenseService.get(id).getApprovalChain().stream().map(s -> s.getRole()).toList());
    }

    @Test
    void multiLevelChainAdvancesStepByStep() {
        var result = submit("alice", "key-multi", "800", "CNY"); // > 500 -> MANAGER+FINANCE
        String id = result.report().getId();
        assertEquals(2, result.report().getApprovalChain().size());

        // 越级审批被拒绝（当前层级是 MANAGER）
        BusinessException e = assertThrows(BusinessException.class,
                () -> expenseService.approve("fin", "FINANCE", id, "skip"));
        assertEquals(ErrorCode.PERMISSION_DENIED, e.getCode());

        expenseService.approve("mgr", "MANAGER", id, "ok");
        assertEquals(ExpenseStatus.SUBMITTED, expenseService.get(id).getStatus());
        expenseService.approve("fin", "FINANCE", id, "ok");
        assertEquals(ExpenseStatus.APPROVED, expenseService.get(id).getStatus());
    }

    // ---------- 6. 权限与审计 ----------

    @Test
    void unauthorizedOperationsAreDeniedAndAudited() {
        var result = submit("alice", "key-auth", "100", "CNY");
        String id = result.report().getId();

        // 角色不匹配
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> expenseService.approve("bob", "INTERN", id, null));
        assertEquals(ErrorCode.PERMISSION_DENIED, e1.getCode());

        // 非申请人撤回
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> expenseService.withdraw("bob", id));
        assertEquals(ErrorCode.PERMISSION_DENIED, e2.getCode());

        // 越权操作留下审计记录
        long denied = auditService.list().stream()
                .filter(a -> AuditService.RESULT_DENIED.equals(a.getResult()))
                .count();
        assertEquals(2, denied);
        // 状态未被越权操作改变
        assertEquals(ExpenseStatus.SUBMITTED, expenseService.get(id).getStatus());
    }

    @Test
    void auditLogIsAppendOnly() {
        submit("alice", "key-audit", "100", "CNY");
        var entries = auditService.list();
        assertFalse(entries.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }
}
