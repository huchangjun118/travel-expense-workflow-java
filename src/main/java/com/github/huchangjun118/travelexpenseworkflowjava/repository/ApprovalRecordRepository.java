package com.github.huchangjun118.travelexpenseworkflowjava.repository;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {
    List<ApprovalRecord> findByReportIdOrderByCreatedAtAsc(Long reportId);
}
