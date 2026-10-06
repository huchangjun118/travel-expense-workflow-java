package com.github.huchangjun118.travelexpenseworkflowjava.domain;

/** 审批链中的一级，提交时由策略快照固化。 */
public class ApprovalStep {
    private int level;
    private String role;
    private Decision decision = Decision.PENDING;
    private String decidedBy;
    private String comment;

    public ApprovalStep() {
    }

    public ApprovalStep(int level, String role) {
        this.level = level;
        this.role = role;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
