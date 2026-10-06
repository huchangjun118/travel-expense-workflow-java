package com.github.huchangjun118.travelexpenseworkflowjava.store;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalEntry;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 账务日志存储（写前日志）。 */
@Component
public class JournalStore {
    private final ConcurrentHashMap<String, JournalEntry> entries = new ConcurrentHashMap<>();

    public JournalEntry save(JournalEntry entry) {
        entries.put(entry.getId(), entry);
        return entry;
    }

    public List<JournalEntry> findByStatus(JournalStatus status) {
        return entries.values().stream().filter(e -> e.getStatus() == status).toList();
    }
}
