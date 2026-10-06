package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditLog;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.FxRate;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.AuditLogRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.FxRateRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理接口：预算账户、汇率维护与审计查询。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record BudgetRequest(String name, BigDecimal totalLimit) {
    }

    public record FxRateRequest(String baseCurrency, String quoteCurrency,
                                BigDecimal rate, LocalDate rateDate) {
    }

    private final BudgetAccountRepository budgetAccountRepository;
    private final FxRateRepository fxRateRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminController(BudgetAccountRepository budgetAccountRepository,
                           FxRateRepository fxRateRepository,
                           AuditLogRepository auditLogRepository) {
        this.budgetAccountRepository = budgetAccountRepository;
        this.fxRateRepository = fxRateRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @PostMapping("/budgets")
    public BudgetAccount createBudget(@RequestBody BudgetRequest request) {
        return budgetAccountRepository.save(new BudgetAccount(request.name(), request.totalLimit()));
    }

    @GetMapping("/budgets")
    public List<BudgetAccount> listBudgets() {
        return budgetAccountRepository.findAll();
    }

    @PostMapping("/fx-rates")
    public FxRate upsertFxRate(@RequestBody FxRateRequest request) {
        FxRate rate = fxRateRepository
                .findByBaseCurrencyAndQuoteCurrency(request.baseCurrency(), request.quoteCurrency())
                .orElseGet(() -> new FxRate(request.baseCurrency(), request.quoteCurrency(),
                        request.rate(), request.rateDate()));
        rate.setRate(request.rate());
        rate.setRateDate(request.rateDate());
        return fxRateRepository.save(rate);
    }

    @GetMapping("/audits")
    public List<AuditLog> audits(@RequestParam(required = false) String resourceId) {
        if (resourceId != null) {
            return auditLogRepository.findByResourceIdOrderByCreatedAtAsc(resourceId);
        }
        return auditLogRepository.findAll();
    }
}
