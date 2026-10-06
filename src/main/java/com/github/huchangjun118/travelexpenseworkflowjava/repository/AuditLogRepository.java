package com.github.huchangjun118.travelexpenseworkflowjava.repository;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 只暴露查询与继承的 save；不定义任何 update/delete 方法，
 * 审计记录只能追加。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByResourceIdOrderByCreatedAtAsc(String resourceId);
}
