package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "expense_report", uniqueConstraints = {
        @UniqueConstraint(name = "uk_expense_idempotency", columnNames = "idempotencyKey")
})
public class ExpenseReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String applicantId;

    @Column(nullable = false)
    private String department;

    @Column(nullable = false)
    private String expenseType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    /** 折算到记账币种后的金额，提交时按汇率快照计算 */
    @Column(precision = 19, scale = 4)
    private BigDecimal bookedAmount;

    /** 提交时刻的汇率快照 */
    @Column(precision = 19, scale = 8)
    private BigDecimal fxRateSnapshot;

    private String fxRateDate;

    private Long budgetAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status = ExpenseStatus.DRAFT;

    /** 当前待审批层级下标，-1 表示无在途审批 */
    private int currentLevel = -1;

    /** 提交周期：每次提交（含重新提交）递增，与预算占用意图配套 */
    private int submitCycle = 0;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("levelIndex ASC")
    private List<ApprovalStep> approvalChain = new ArrayList<>();

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected ExpenseReport() {
    }

    public ExpenseReport(String idempotencyKey, String applicantId, String department,
                         String expenseType, BigDecimal amount, String currency) {
        this.idempotencyKey = idempotencyKey;
        this.applicantId = applicantId;
        this.department = department;
        this.expenseType = expenseType;
        this.amount = amount;
        this.currency = currency;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getApplicantId() { return applicantId; }
    public String getDepartment() { return department; }
    public String getExpenseType() { return expenseType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getBookedAmount() { return bookedAmount; }
    public void setBookedAmount(BigDecimal bookedAmount) { this.bookedAmount = bookedAmount; }
    public BigDecimal getFxRateSnapshot() { return fxRateSnapshot; }
    public void setFxRateSnapshot(BigDecimal fxRateSnapshot) { this.fxRateSnapshot = fxRateSnapshot; }
    public String getFxRateDate() { return fxRateDate; }
    public void setFxRateDate(String fxRateDate) { this.fxRateDate = fxRateDate; }
    public Long getBudgetAccountId() { return budgetAccountId; }
    public void setBudgetAccountId(Long budgetAccountId) { this.budgetAccountId = budgetAccountId; }
    public ExpenseStatus getStatus() { return status; }
    public void setStatus(ExpenseStatus status) { this.status = status; }
    public int getCurrentLevel() { return currentLevel; }
    public int getSubmitCycle() { return submitCycle; }
    public void setSubmitCycle(int submitCycle) { this.submitCycle = submitCycle; }
    public void setCurrentLevel(int currentLevel) { this.currentLevel = currentLevel; }
    public List<ApprovalStep> getApprovalChain() { return approvalChain; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
