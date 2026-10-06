package com.github.huchangjun118.travelexpenseworkflowjava.domain;

/**
 * 报销单状态机：
 * DRAFT -> SUBMITTED -> APPROVED / REJECTED / WITHDRAWN
 * REJECTED/WITHDRAWN -> (resubmit) SUBMITTED
 */
public enum ExpenseStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
