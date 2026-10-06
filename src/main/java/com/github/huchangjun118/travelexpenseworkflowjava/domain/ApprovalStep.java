package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;

/**
 * 提交时刻由策略解析并固化到单据上的审批层级。
 */
@Entity
@Table(name = "approval_step")
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private ExpenseReport report;

    private int levelIndex;

    /** 该层级要求的审批人角色 */
    @Column(nullable = false)
    private String approverRole;

    protected ApprovalStep() {
    }

    public ApprovalStep(ExpenseReport report, int levelIndex, String approverRole) {
        this.report = report;
        this.levelIndex = levelIndex;
        this.approverRole = approverRole;
    }

    public Long getId() { return id; }
    public int getLevelIndex() { return levelIndex; }
    public String getApproverRole() { return approverRole; }
}
