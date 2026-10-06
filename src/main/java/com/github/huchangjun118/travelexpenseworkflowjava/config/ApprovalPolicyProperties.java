package com.github.huchangjun118.travelexpenseworkflowjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批策略配置。按规则顺序匹配：金额上限、费用类型、部门均匹配时采用该规则的审批层级。
 * 规则在提交时刻解析并固化到单据，后续配置变更不影响在途单据。
 */
@ConfigurationProperties(prefix = "approval.policy")
public class ApprovalPolicyProperties {

    public static class Rule {
        /** 金额（记账币种）小于等于该值时匹配，null 表示不限 */
        private BigDecimal maxAmount;
        /** 匹配的费用类型，null 表示不限 */
        private String expenseType;
        /** 匹配的部门，null 表示不限 */
        private String department;
        /** 审批层级，依次为各层审批人角色 */
        private List<String> levels = new ArrayList<>();

        public BigDecimal getMaxAmount() { return maxAmount; }
        public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
        public String getExpenseType() { return expenseType; }
        public void setExpenseType(String expenseType) { this.expenseType = expenseType; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public List<String> getLevels() { return levels; }
        public void setLevels(List<String> levels) { this.levels = levels; }
    }

    private List<Rule> rules = new ArrayList<>();

    /** 无规则匹配时使用的默认审批层级 */
    private List<String> defaultLevels = new ArrayList<>(List.of("MANAGER"));

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }
    public List<String> getDefaultLevels() { return defaultLevels; }
    public void setDefaultLevels(List<String> defaultLevels) { this.defaultLevels = defaultLevels; }
}
