package com.github.huchangjun118.travelexpenseworkflowjava.config;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExchangeRateService;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/** 本地独立启动时预置演示数据：默认预算账户与常用汇率。 */
@Component
public class DemoDataSeeder implements ApplicationRunner {
    private final BudgetAccountStore accountStore;
    private final ExchangeRateService exchangeRateService;
    private final ExpenseProperties properties;

    public DemoDataSeeder(BudgetAccountStore accountStore,
                          ExchangeRateService exchangeRateService,
                          ExpenseProperties properties) {
        this.accountStore = accountStore;
        this.exchangeRateService = exchangeRateService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        accountStore.save(new BudgetAccount("acc-eng", "ENG", new BigDecimal("100000")));
        accountStore.save(new BudgetAccount("acc-sales", "SALES", new BigDecimal("50000")));
        exchangeRateService.putRate("USD", properties.getLedgerCurrency(),
                new BigDecimal("7.10"), Instant.now());
        exchangeRateService.putRate("EUR", properties.getLedgerCurrency(),
                new BigDecimal("7.80"), Instant.now());
    }
}
