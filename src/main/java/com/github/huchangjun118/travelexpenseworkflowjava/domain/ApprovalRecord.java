package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "approval_record")
public class ApprovalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    private int levelIndex;

    @Column(nullable = false)
    private String approverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalAction action;

    private String comment;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRecord() {
    }

    public ApprovalRecord(Long reportId, int levelIndex, String approverId, ApprovalAction action, String comment) {
        this.reportId = reportId;
        this.levelIndex = levelIndex;
        this.approverId = approverId;
        this.action = action;
        this.comment = comment;
    }

    public Long getId() { return id; }
    public Long getReportId() { return reportId; }
    public int getLevelIndex() { return levelIndex; }
    public String getApproverId() { return approverId; }
    public ApprovalAction getAction() { return action; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
