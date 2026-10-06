package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 预算账户：总额、已占用(held)、已核销(consumed)。 */
public class BudgetAccount {
    private String id;
    private String department;
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal held = BigDecimal.ZERO;
    private BigDecimal consumed = BigDecimal.ZERO;
    /** 已应用的账务操作日志 id，用于恢复时幂等重放。 */
    private final Set<String> appliedJournalIds = ConcurrentHashMap.newKeySet();

    public BudgetAccount() {
    }

    public BudgetAccount(String id, String department, BigDecimal total) {
        this.id = id;
        this.department = department;
        this.total = total;
    }

    public BigDecimal available() {
        return total.subtract(held).subtract(consumed);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public BigDecimal getHeld() {
        return held;
    }

    public void setHeld(BigDecimal held) {
        this.held = held;
    }

    public BigDecimal getConsumed() {
        return consumed;
    }

    public void setConsumed(BigDecimal consumed) {
        this.consumed = consumed;
    }

    public Set<String> getAppliedJournalIds() {
        return appliedJournalIds;
    }
}
