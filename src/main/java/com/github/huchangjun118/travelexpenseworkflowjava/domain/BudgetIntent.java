package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预算占用/释放意图。两阶段写入：先以 PENDING 落库（独立事务），
 * 再在业务事务中应用预算变动并置为 COMMITTED。
 * 若进程在两阶段之间崩溃，重启时按 PENDING 记录恢复（继续完成或整体回滚）。
 * (reportId, type, cycle) 唯一保证同一单据同一提交周期内占用/释放只生效一次。
 */
@Entity
@Table(name = "budget_intent", uniqueConstraints = {
        @UniqueConstraint(name = "uk_intent_report_type_cycle", columnNames = {"reportId", "type", "cycle"})
})
public class BudgetIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentType type;

    /** 单据的提交周期，重新提交时递增，允许再次占用 */
    @Column(nullable = false)
    private int cycle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentStatus status = IntentStatus.PENDING;

    /** 该意图完成后单据应处于的状态，供崩溃恢复时推进状态机 */
    @Column(nullable = false)
    private String targetReportStatus;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected BudgetIntent() {
    }

    public BudgetIntent(Long reportId, Long accountId, BigDecimal amount, IntentType type, int cycle,
                        String targetReportStatus) {
        this.reportId = reportId;
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.cycle = cycle;
        this.targetReportStatus = targetReportStatus;
    }

    public Long getId() { return id; }
    public Long getReportId() { return reportId; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public IntentType getType() { return type; }
    public int getCycle() { return cycle; }
    public String getTargetReportStatus() { return targetReportStatus; }
    public IntentStatus getStatus() { return status; }
    public void setStatus(IntentStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
