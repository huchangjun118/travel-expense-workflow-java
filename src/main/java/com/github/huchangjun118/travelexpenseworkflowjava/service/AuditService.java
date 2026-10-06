package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditEntry;
import com.github.huchangjun118.travelexpenseworkflowjava.store.AuditStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 追加式审计服务：只追加，不提供修改或删除入口。 */
@Service
public class AuditService {
    public static final String RESULT_OK = "OK";
    public static final String RESULT_DENIED = "DENIED";
    public static final String RESULT_REJECTED = "REJECTED";

    private final AuditStore store;

    public AuditService(AuditStore store) {
        this.store = store;
    }

    public void record(String userId, String action, String reportId, String result, String detail) {
        store.append(new AuditEntry(UUID.randomUUID().toString(), Instant.now(),
                userId, action, reportId, result, detail));
    }

    public List<AuditEntry> list() {
        return store.list();
    }
}
