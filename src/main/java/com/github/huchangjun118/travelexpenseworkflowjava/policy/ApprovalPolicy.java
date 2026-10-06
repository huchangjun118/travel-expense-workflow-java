package com.github.huchangjun118.travelexpenseworkflowjava.policy;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ApprovalStep;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 审批策略：按金额（记账币种）、费用类型、部门顺序匹配，
 * 命中第一条规则即生成审批链；调用方负责在提交时刻把结果固化到单据上。
 */
@Component
public class ApprovalPolicy {
    private final ExpenseProperties properties;

    public ApprovalPolicy(ExpenseProperties properties) {
        this.properties = properties;
    }

    public List<ApprovalStep> resolveChain(BigDecimal ledgerAmount, String expenseType, String department) {
        for (ExpenseProperties.PolicyRule rule : properties.getPolicyRules()) {
            if (rule.getMaxAmount() != null && ledgerAmount.compareTo(rule.getMaxAmount()) > 0) {
                continue;
            }
            if (rule.getExpenseType() != null && !rule.getExpenseType().equalsIgnoreCase(expenseType)) {
                continue;
            }
            if (rule.getDepartment() != null && !rule.getDepartment().equalsIgnoreCase(department)) {
                continue;
            }
            if (rule.getApproverRoles().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "命中的审批策略未配置审批角色");
            }
            return IntStream.range(0, rule.getApproverRoles().size())
                    .mapToObj(i -> new ApprovalStep(i, rule.getApproverRoles().get(i)))
                    .toList();
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "无匹配的审批策略: amount=" + ledgerAmount + ", type=" + expenseType + ", dept=" + department);
    }
}
