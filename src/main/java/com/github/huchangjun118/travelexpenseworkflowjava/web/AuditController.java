package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.AuditEntry;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import com.github.huchangjun118.travelexpenseworkflowjava.service.AuditService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审计查询接口：仅 ADMIN 角色可读。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditEntry> list(@RequestHeader("X-User-Id") String userId,
                                @RequestHeader("X-User-Role") String role) {
        if (!"ADMIN".equals(role)) {
            auditService.record(userId, "LIST_AUDIT", null, AuditService.RESULT_DENIED,
                    "非 ADMIN 角色查询审计日志");
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅 ADMIN 可查询审计日志");
        }
        return auditService.list();
    }
}
