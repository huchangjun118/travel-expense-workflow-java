package com.github.huchangjun118.travelexpenseworkflowjava.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时执行账务日志恢复。 */
@Component
public class RecoveryRunner implements ApplicationRunner {
    private final BudgetService budgetService;

    public RecoveryRunner(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        budgetService.recoverPendingJournals();
    }
}
