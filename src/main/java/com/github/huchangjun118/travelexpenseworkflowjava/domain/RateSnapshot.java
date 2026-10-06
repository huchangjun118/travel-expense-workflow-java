package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 汇率快照：提交时刻固化，保证折算结果可追溯。 */
public class RateSnapshot {
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal rate;
    private Instant rateFetchedAt;
    private BigDecimal ledgerAmount;

    public RateSnapshot() {
    }

    public RateSnapshot(String fromCurrency, String toCurrency, BigDecimal rate,
                        Instant rateFetchedAt, BigDecimal ledgerAmount) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rate = rate;
        this.rateFetchedAt = rateFetchedAt;
        this.ledgerAmount = ledgerAmount;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public void setFromCurrency(String fromCurrency) {
        this.fromCurrency = fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public void setToCurrency(String toCurrency) {
        this.toCurrency = toCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public Instant getRateFetchedAt() {
        return rateFetchedAt;
    }

    public void setRateFetchedAt(Instant rateFetchedAt) {
        this.rateFetchedAt = rateFetchedAt;
    }

    public BigDecimal getLedgerAmount() {
        return ledgerAmount;
    }

    public void setLedgerAmount(BigDecimal ledgerAmount) {
        this.ledgerAmount = ledgerAmount;
    }
}
