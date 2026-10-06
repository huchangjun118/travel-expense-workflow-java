package com.github.huchangjun118.travelexpenseworkflowjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 差旅报销配置项。 */
@Component
@ConfigurationProperties(prefix = "expense")
public class ExpenseProperties {
    /** 记账币种。 */
    private String ledgerCurrency = "CNY";
    /** 汇率最大有效秒数，超过视为过期。 */
    private long rateMaxAgeSeconds = 86400;
    /** 崩溃恢复策略：COMPLETE（继续完成）或 ROLLBACK（整体回滚）。 */
    private String recoveryStrategy = "COMPLETE";
    /** 审批策略规则，按顺序匹配，取第一条命中的规则。 */
    private List<PolicyRule> policyRules = new ArrayList<>();

    public static class PolicyRule {
        /** 金额上限（记账币种），null 表示不限。 */
        private BigDecimal maxAmount;
        /** 费用类型，null 表示不限。 */
        private String expenseType;
        /** 部门，null 表示不限。 */
        private String department;
        /** 命中后固化的审批角色链。 */
        private List<String> approverRoles = new ArrayList<>();

        public BigDecimal getMaxAmount() {
            return maxAmount;
        }

        public void setMaxAmount(BigDecimal maxAmount) {
            this.maxAmount = maxAmount;
        }

        public String getExpenseType() {
            return expenseType;
        }

        public void setExpenseType(String expenseType) {
            this.expenseType = expenseType;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }

        public List<String> getApproverRoles() {
            return approverRoles;
        }

        public void setApproverRoles(List<String> approverRoles) {
            this.approverRoles = approverRoles;
        }
    }

    public String getLedgerCurrency() {
        return ledgerCurrency;
    }

    public void setLedgerCurrency(String ledgerCurrency) {
        this.ledgerCurrency = ledgerCurrency;
    }

    public long getRateMaxAgeSeconds() {
        return rateMaxAgeSeconds;
    }

    public void setRateMaxAgeSeconds(long rateMaxAgeSeconds) {
        this.rateMaxAgeSeconds = rateMaxAgeSeconds;
    }

    public String getRecoveryStrategy() {
        return recoveryStrategy;
    }

    public void setRecoveryStrategy(String recoveryStrategy) {
        this.recoveryStrategy = recoveryStrategy;
    }

    public List<PolicyRule> getPolicyRules() {
        return policyRules;
    }

    public void setPolicyRules(List<PolicyRule> policyRules) {
        this.policyRules = policyRules;
    }
}
