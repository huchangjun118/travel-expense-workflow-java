package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ApprovalPolicyProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ApprovalPolicyService {

    private final ApprovalPolicyProperties policy;

    public ApprovalPolicyService(ApprovalPolicyProperties policy) {
        this.policy = policy;
    }

    /**
     * 依据金额（记账币种）、费用类型与部门匹配审批层级。
     * 在所有匹配规则中取限定条件最多（最具体）的一条；并列时取先配置者。
     * 无匹配时使用默认层级。
     */
    public List<String> resolveChain(String department, String expenseType, BigDecimal bookedAmount) {
        ApprovalPolicyProperties.Rule best = null;
        int bestSpecificity = -1;
        for (ApprovalPolicyProperties.Rule rule : policy.getRules()) {
            if (rule.getDepartment() != null && !rule.getDepartment().equals(department)) {
                continue;
            }
            if (rule.getExpenseType() != null && !rule.getExpenseType().equals(expenseType)) {
                continue;
            }
            if (rule.getMaxAmount() != null && bookedAmount.compareTo(rule.getMaxAmount()) > 0) {
                continue;
            }
            // 类别条件（部门/费用类型）比金额范围更具体：权重 4/2/1
            int specificity = (rule.getDepartment() != null ? 4 : 0)
                    + (rule.getExpenseType() != null ? 2 : 0)
                    + (rule.getMaxAmount() != null ? 1 : 0);
            if (specificity > bestSpecificity) {
                best = rule;
                bestSpecificity = specificity;
            }
        }
        if (best != null) {
            return List.copyOf(best.getLevels());
        }
        return List.copyOf(policy.getDefaultLevels());
    }
}
