package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 汇率及其获取时间。 */
public class ExchangeRate {
    private final String fromCurrency;
    private final String toCurrency;
    private final BigDecimal rate;
    private final Instant fetchedAt;

    public ExchangeRate(String fromCurrency, String toCurrency, BigDecimal rate, Instant fetchedAt) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rate = rate;
        this.fetchedAt = fetchedAt;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
