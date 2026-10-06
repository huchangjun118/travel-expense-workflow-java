package com.github.huchangjun118.travelexpenseworkflowjava.store;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 报销单存储（内存实现，可替换为持久化实现）。 */
@Component
public class ExpenseReportStore {
    private final ConcurrentHashMap<String, ExpenseReport> reports = new ConcurrentHashMap<>();

    public ExpenseReport save(ExpenseReport report) {
        reports.put(report.getId(), report);
        return report;
    }

    public Optional<ExpenseReport> findById(String id) {
        return Optional.ofNullable(reports.get(id));
    }
}
