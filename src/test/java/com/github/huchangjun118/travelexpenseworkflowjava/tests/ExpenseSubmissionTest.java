package com.github.huchangjun118.travelexpenseworkflowjava.tests;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ApprovalPolicyProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.FxRate;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.FxRateRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ErrorCodes;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ExpenseSubmissionTest {

    @Autowired
    ExpenseService expenseService;
    @Autowired
    ExpenseReportRepository reportRepository;
    @Autowired
    BudgetAccountRepository budgetAccountRepository;
    @Autowired
    FxRateRepository fxRateRepository;
    @Autowired
    ApprovalPolicyProperties policyProperties;

    Long budgetId;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        budgetAccountRepository.deleteAll();
        fxRateRepository.deleteAll();
        BudgetAccount account = budgetAccountRepository.save(new BudgetAccount("IT-2026", new BigDecimal("10000")));
        budgetId = account.getId();
    }

    @Test
    void duplicateSubmitReturnsSameReportAndHoldsBudgetOnce() {
        var first = expenseService.submit("key-1", "alice", "IT", "TRAVEL",
                new BigDecimal("500"), "CNY", budgetId);
        assertFalse(first.duplicated());
        assertEquals(ExpenseStatus.SUBMITTED, first.report().getStatus());

        var second = expenseService.submit("key-1", "alice", "IT", "TRAVEL",
                new BigDecimal("500"), "CNY", budgetId);
        assertTrue(second.duplicated());
        assertEquals(first.report().getId(), second.report().getId());

        assertEquals(1, reportRepository.count());
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, new BigDecimal("500.0000").compareTo(account.getUsedAmount()));
    }

    @Test
    void concurrentDuplicateSubmitCreatesOnlyOneReport() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ExpenseService.SubmitResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> expenseService.submit("key-concurrent", "alice", "IT", "TRAVEL",
                    new BigDecimal("500"), "CNY", budgetId));
        }
        List<Future<ExpenseService.SubmitResult>> futures = pool.invokeAll(tasks);
        Long firstId = null;
        for (Future<ExpenseService.SubmitResult> f : futures) {
            ExpenseService.SubmitResult r = f.get();
            if (firstId == null) {
                firstId = r.report().getId();
            }
            assertEquals(firstId, r.report().getId());
        }
        pool.shutdown();
        assertEquals(1, reportRepository.count());
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, new BigDecimal("500.0000").compareTo(account.getUsedAmount()),
                "budget must be held exactly once");
    }

    @Test
    void missingFxRateIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                expenseService.submit("key-fx-1", "alice", "IT", "TRAVEL",
                        new BigDecimal("100"), "USD", budgetId));
        assertEquals(ErrorCodes.FX_RATE_UNAVAILABLE, ex.getCode());
        assertEquals(0, reportRepository.count(), "no partial report may be persisted");
    }

    @Test
    void staleFxRateIsRejected() {
        fxRateRepository.save(new FxRate("USD", "CNY", new BigDecimal("7.2"),
                LocalDate.now().minusDays(30)));
        BusinessException ex = assertThrows(BusinessException.class, () ->
                expenseService.submit("key-fx-2", "alice", "IT", "TRAVEL",
                        new BigDecimal("100"), "USD", budgetId));
        assertEquals(ErrorCodes.FX_RATE_UNAVAILABLE, ex.getCode());
    }

    @Test
    void foreignCurrencyIsConvertedWithSnapshot() {
        fxRateRepository.save(new FxRate("USD", "CNY", new BigDecimal("7.2"), LocalDate.now()));
        var result = expenseService.submit("key-fx-3", "alice", "IT", "TRAVEL",
                new BigDecimal("100"), "USD", budgetId);
        ExpenseReport report = result.report();
        assertEquals(0, new BigDecimal("720.0000").compareTo(report.getBookedAmount()));
        assertEquals(0, new BigDecimal("7.2").compareTo(report.getFxRateSnapshot()));
        assertEquals(LocalDate.now().toString(), report.getFxRateDate());
        // 预算按记账币种占用
        BudgetAccount account = budgetAccountRepository.findById(budgetId).orElseThrow();
        assertEquals(0, new BigDecimal("720.0000").compareTo(account.getUsedAmount()));
    }

    @Test
    void approvalChainIsResolvedByPolicyAndFrozenAtSubmit() {
        // 500 CNY 命中 maxAmount=1000 规则 -> 仅 MANAGER 一级
        var small = expenseService.submit("key-chain-1", "alice", "IT", "TRAVEL",
                new BigDecimal("500"), "CNY", budgetId);
        assertEquals(List.of("MANAGER"),
                small.report().getApprovalChain().stream().map(s -> s.getApproverRole()).toList());

        // 5000 CNY 命中 maxAmount=10000 规则 -> MANAGER + DIRECTOR
        var mid = expenseService.submit("key-chain-2", "alice", "IT", "TRAVEL",
                new BigDecimal("5000"), "CNY", budgetId);
        assertEquals(List.of("MANAGER", "DIRECTOR"),
                mid.report().getApprovalChain().stream().map(s -> s.getApproverRole()).toList());

        // FINANCE 部门命中部门规则 -> 三级
        var fin = expenseService.submit("key-chain-3", "bob", "FINANCE", "TRAVEL",
                new BigDecimal("100"), "CNY", budgetId);
        assertEquals(List.of("MANAGER", "DIRECTOR", "CFO"),
                fin.report().getApprovalChain().stream().map(s -> s.getApproverRole()).toList());

        // 策略变更不影响在途单据
        List<String> originalDefault = policyProperties.getDefaultLevels();
        policyProperties.setDefaultLevels(List.of("CEO"));
        try {
            ExpenseReport reloaded = expenseService.get(fin.report().getId());
            assertEquals(List.of("MANAGER", "DIRECTOR", "CFO"),
                    reloaded.getApprovalChain().stream().map(s -> s.getApproverRole()).toList());
        } finally {
            policyProperties.setDefaultLevels(originalDefault);
        }
    }
}
