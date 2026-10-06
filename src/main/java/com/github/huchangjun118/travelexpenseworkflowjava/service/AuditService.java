package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditLog;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 追加审计记录。使用独立事务，保证即使业务操作回滚，
     * 越权/失败等关键事件仍然留痕。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actorId, String action, String resourceId, String result, String detail) {
        auditLogRepository.save(new AuditLog(actorId, action, resourceId, result, detail));
    }
}
