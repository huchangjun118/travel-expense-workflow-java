package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetIntent;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BudgetConcurrencyTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;
    @Autowired
    BudgetIntentRepository intentRepository;

    Long budgetId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        intentRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        budgetId = budgetAccountRepository.save(new BudgetAccount("IT-2026", new BigDecimal("1000"))).getId();
    }

    @Test
    void overLimitSubmitIsRejectedWithDistinctReason() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                expenseService.submit("big-1", "alice", "IT", "TRAVEL",
                        new BigDecimal("2000"), "CNY", budgetId));
        assertEquals(ErrorCodes.BUDGET_EXCEEDED, ex.getCode());
        // 预算未被占用
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getUsedAmount()));
    }

    @Test
    void concurrentSubmitsNeverExceedBudget() throws Exception {
        // 限额 1000，每单 300，最多成功 3 单
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger exceeded = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            tasks.add(() -> {
                try {
                    expenseService.submit("conc-" + n, "alice", "IT", "TRAVEL",
                            new BigDecimal("300"), "CNY", budgetId);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    assertEquals(ErrorCodes.BUDGET_EXCEEDED, e.getCode());
                    exceeded.incrementAndGet();
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        assertEquals(3, success.get());
        assertEquals(7, exceeded.get());
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, new BigDecimal("900.0000").compareTo(account.getUsedAmount()),
                "used amount must never exceed the limit");
    }

    @Test
    void concurrentRejectAndWithdrawReleasesOnlyOnce() throws Exception {
        var result = expenseService.submit("rw-1", "alice", "IT", "TRAVEL",
                new BigDecimal("400"), "CNY", budgetId);
        Long reportId = result.report().getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        expenseService.reject(reportId, "mgr", "MANAGER", "no");
                        return "reject-ok";
                    } catch (BusinessException e) {
                        return "reject-" + e.getCode();
                    }
                },
                () -> {
                    try {
                        expenseService.withdraw(reportId, "alice");
                        return "withdraw-ok";
                    } catch (BusinessException e) {
                        return "withdraw-" + e.getCode();
                    }
                });
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(tasks)) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        // 恰好一个成功，另一个因非法流转被拒
        long okCount = outcomes.stream().filter(o -> o.endsWith("-ok")).count();
        long illegalCount = outcomes.stream()
                .filter(o -> o.endsWith(ErrorCodes.ILLEGAL_STATE_TRANSITION)).count();
        assertEquals(1, okCount, "outcomes=" + outcomes);
        assertEquals(1, illegalCount, "outcomes=" + outcomes);

        // 释放只生效一次
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getUsedAmount()),
                "budget must be fully released exactly once");
        List<BudgetIntent> releases = intentRepository.findAll().stream()
                .filter(i -> i.getType() == IntentType.RELEASE
                        && i.getReportId().equals(reportId)
                        && i.getStatus() == IntentStatus.COMMITTED)
                .toList();
        assertEquals(1, releases.size());

        ExpenseStatus finalStatus = expenseService.get(reportId).getStatus();
        assertTrue(finalStatus == ExpenseStatus.REJECTED || finalStatus == ExpenseStatus.WITHDRAWN);
    }

    @Test
    void resubmitAfterWithdrawHoldsBudgetAgain() {
        var result = expenseService.submit("re-1", "alice", "IT", "TRAVEL",
                new BigDecimal("400"), "CNY", budgetId);
        Long reportId = result.report().getId();
        expenseService.withdraw(reportId, "alice");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));

        expenseService.resubmit(reportId, "alice");
        assertEquals(ExpenseStatus.SUBMITTED, expenseService.get(reportId).getStatus());
        assertEquals(0, new BigDecimal("400.0000").compareTo(
                budgetAccountRepository.findById(budgetId).orElseThrow().getUsedAmount()));
    }
}
