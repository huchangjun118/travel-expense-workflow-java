package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "fx_rate", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fx_pair", columnNames = {"baseCurrency", "quoteCurrency"})
})
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String baseCurrency;

    @Column(nullable = false)
    private String quoteCurrency;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private LocalDate rateDate;

    protected FxRate() {
    }

    public FxRate(String baseCurrency, String quoteCurrency, BigDecimal rate, LocalDate rateDate) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.rateDate = rateDate;
    }

    public Long getId() { return id; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public void setRateDate(LocalDate rateDate) { this.rateDate = rateDate; }
    public LocalDate getRateDate() { return rateDate; }
}
