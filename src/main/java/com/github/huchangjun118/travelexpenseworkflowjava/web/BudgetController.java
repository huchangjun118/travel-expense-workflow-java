package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.service.BudgetService;
import org.springframework.web.bind.annotation.*;

/** 预算账户查询接口。 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping("/{accountId}")
    public BudgetAccount get(@PathVariable String accountId) {
        return budgetService.getAccount(accountId);
    }
}
