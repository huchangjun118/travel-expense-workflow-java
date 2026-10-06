package com.github.huchangjun118.travelexpenseworkflowjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "expense")
public class ExpenseProperties {

    /** 记账币种 */
    private String bookCurrency = "CNY";

    /** 汇率允许的最大滞后天数，超过视为过期并拒绝入账 */
    private int fxMaxAgeDays = 7;

    /** 崩溃恢复策略：COMMIT 继续完成未完成的占用/释放，ROLLBACK 整体回滚 */
    private String recoveryStrategy = "COMMIT";

    public String getBookCurrency() { return bookCurrency; }
    public void setBookCurrency(String bookCurrency) { this.bookCurrency = bookCurrency; }
    public int getFxMaxAgeDays() { return fxMaxAgeDays; }
    public void setFxMaxAgeDays(int fxMaxAgeDays) { this.fxMaxAgeDays = fxMaxAgeDays; }
    public String getRecoveryStrategy() { return recoveryStrategy; }
    public void setRecoveryStrategy(String recoveryStrategy) { this.recoveryStrategy = recoveryStrategy; }
}
