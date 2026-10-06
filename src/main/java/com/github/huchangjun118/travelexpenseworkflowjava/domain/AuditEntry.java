package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import java.time.Instant;

/** 追加式审计记录，一旦写入不可修改或删除。 */
public class AuditEntry {
    private final String id;
    private final Instant timestamp;
    private final String userId;
    private final String action;
    private final String reportId;
    private final String result;
    private final String detail;

    public AuditEntry(String id, Instant timestamp, String userId, String action,
                      String reportId, String result, String detail) {
        this.id = id;
        this.timestamp = timestamp;
        this.userId = userId;
        this.action = action;
        this.reportId = reportId;
        this.result = result;
        this.detail = detail;
    }

    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getReportId() {
        return reportId;
    }

    public String getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }
}
