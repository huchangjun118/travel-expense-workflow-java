package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "budget_account")
public class BudgetAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal usedAmount = BigDecimal.ZERO;

    @Version
    private long version;

    protected BudgetAccount() {
    }

    public BudgetAccount(String name, BigDecimal totalLimit) {
        this.name = name;
        this.totalLimit = totalLimit;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getTotalLimit() { return totalLimit; }
    public BigDecimal getUsedAmount() { return usedAmount; }
    public void setUsedAmount(BigDecimal usedAmount) { this.usedAmount = usedAmount; }
    public long getVersion() { return version; }

    public BigDecimal available() {
        return totalLimit.subtract(usedAmount);
    }
}
