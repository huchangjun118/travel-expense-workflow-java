package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 报销单聚合。 */
public class ExpenseReport {
    private String id;
    private String applicantId;
    private Money amount;
    private ExpenseType expenseType;
    private String department;
    private String description;
    private ExpenseStatus status = ExpenseStatus.DRAFT;
    /** 提交时刻固化的审批链快照。 */
    private List<ApprovalStep> approvalChain = new ArrayList<>();
    private int currentStepIndex;
    /** 提交时刻固化的汇率快照与记账币种金额。 */
    private RateSnapshot rateSnapshot;
    private String budgetAccountId;
    private HoldState holdState = HoldState.NONE;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApplicantId() {
        return applicantId;
    }

    public void setApplicantId(String applicantId) {
        this.applicantId = applicantId;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public ExpenseType getExpenseType() {
        return expenseType;
    }

    public void setExpenseType(ExpenseType expenseType) {
        this.expenseType = expenseType;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ExpenseStatus getStatus() {
        return status;
    }

    public void setStatus(ExpenseStatus status) {
        this.status = status;
    }

    public List<ApprovalStep> getApprovalChain() {
        return approvalChain;
    }

    public void setApprovalChain(List<ApprovalStep> approvalChain) {
        this.approvalChain = approvalChain;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public RateSnapshot getRateSnapshot() {
        return rateSnapshot;
    }

    public void setRateSnapshot(RateSnapshot rateSnapshot) {
        this.rateSnapshot = rateSnapshot;
    }

    public String getBudgetAccountId() {
        return budgetAccountId;
    }

    public void setBudgetAccountId(String budgetAccountId) {
        this.budgetAccountId = budgetAccountId;
    }

    public HoldState getHoldState() {
        return holdState;
    }

    public void setHoldState(HoldState holdState) {
        this.holdState = holdState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
