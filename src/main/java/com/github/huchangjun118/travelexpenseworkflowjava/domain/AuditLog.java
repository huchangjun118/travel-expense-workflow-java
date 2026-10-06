package com.github.huchangjun118.travelexpenseworkflowjava.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 追加式审计日志：只提供新增，不提供任何修改/删除入口。
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actorId;

    @Column(nullable = false)
    private String action;

    private String resourceId;

    /** SUCCESS / DENIED / REJECTED */
    @Column(nullable = false)
    private String result;

    @Column(length = 1024)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {
    }

    public AuditLog(String actorId, String action, String resourceId, String result, String detail) {
        this.actorId = actorId;
        this.action = action;
        this.resourceId = resourceId;
        this.result = result;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public String getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getResourceId() { return resourceId; }
    public String getResult() { return result; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
