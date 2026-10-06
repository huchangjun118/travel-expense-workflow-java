package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExchangeRate;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.Money;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.RateSnapshot;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 汇率服务：缺失或过期一律拒绝入账，绝不静默使用默认值。 */
@Service
public class ExchangeRateService {
    private final ExpenseProperties properties;
    private final Map<String, ExchangeRate> rates = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public ExchangeRateService(ExpenseProperties properties) {
        this.properties = properties;
    }

    /** 测试可注入时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void putRate(String fromCurrency, String toCurrency, BigDecimal rate, Instant fetchedAt) {
        rates.put(key(fromCurrency, toCurrency), new ExchangeRate(fromCurrency, toCurrency, rate, fetchedAt));
    }

    /**
     * 折算到记账币种并生成可追溯快照。
     * 汇率缺失抛 RATE_MISSING，过期抛 RATE_EXPIRED。
     */
    public RateSnapshot toLedger(Money money) {
        if (money == null || money.getAmount() == null || money.getCurrency() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "金额与币种不能为空");
        }
        String ledger = properties.getLedgerCurrency();
        if (ledger.equals(money.getCurrency())) {
            return new RateSnapshot(money.getCurrency(), ledger, BigDecimal.ONE,
                    Instant.now(clock), money.getAmount());
        }
        ExchangeRate rate = rates.get(key(money.getCurrency(), ledger));
        if (rate == null) {
            throw new BusinessException(ErrorCode.RATE_MISSING,
                    "缺少汇率: " + money.getCurrency() + "->" + ledger);
        }
        long ageSeconds = Instant.now(clock).getEpochSecond() - rate.getFetchedAt().getEpochSecond();
        if (ageSeconds > properties.getRateMaxAgeSeconds()) {
            throw new BusinessException(ErrorCode.RATE_EXPIRED,
                    "汇率已过期: " + money.getCurrency() + "->" + ledger
                            + ", age=" + ageSeconds + "s, max=" + properties.getRateMaxAgeSeconds() + "s");
        }
        BigDecimal ledgerAmount = money.getAmount().multiply(rate.getRate())
                .setScale(2, RoundingMode.HALF_UP);
        return new RateSnapshot(money.getCurrency(), ledger, rate.getRate(),
                rate.getFetchedAt(), ledgerAmount);
    }

    private static String key(String from, String to) {
        return from + "->" + to;
    }
}
