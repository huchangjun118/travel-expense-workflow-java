package com.github.huchangjun118.travelexpenseworkflowjava.store;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 幂等键存储：同一提交键只映射到一份单据。 */
@Component
public class IdempotencyStore {
    private final ConcurrentHashMap<String, String> keyToReportId = new ConcurrentHashMap<>();

    public Optional<String> findReportId(String key) {
        return Optional.ofNullable(keyToReportId.get(key));
    }

    /** 仅当键不存在时写入，返回已存在的值或 null。 */
    public String putIfAbsent(String key, String reportId) {
        return keyToReportId.putIfAbsent(key, reportId);
    }
}
