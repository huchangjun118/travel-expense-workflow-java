package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 预算占用/释放/核销的写前日志，用于崩溃恢复。 */
public class JournalEntry {
    private final String id;
    private final String reportId;
    private final String accountId;
    private final BigDecimal amount;
    private final JournalType type;
    private final Instant createdAt;
    private volatile JournalStatus status = JournalStatus.PENDING;

    public JournalEntry(String id, String reportId, String accountId, BigDecimal amount, JournalType type) {
        this.id = id;
        this.reportId = reportId;
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getReportId() {
        return reportId;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public JournalType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public JournalStatus getStatus() {
        return status;
    }

    public void setStatus(JournalStatus status) {
        this.status = status;
    }
}
