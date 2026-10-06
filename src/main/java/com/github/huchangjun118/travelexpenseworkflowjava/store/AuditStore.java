package com.github.huchangjun118.travelexpenseworkflowjava.store;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 审计存储：只暴露追加与读取，不提供修改/删除。 */
@Component
public class AuditStore {
    private final CopyOnWriteArrayList<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void append(AuditEntry entry) {
        entries.add(entry);
    }

    public List<AuditEntry> list() {
        return List.copyOf(entries);
    }
}
