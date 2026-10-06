package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;

import java.math.BigDecimal;

/** 提交响应：duplicate 标识可识别的重复提交结果。 */
public record SubmitResponse(String reportId, String status, boolean duplicate,
                             BigDecimal ledgerAmount, String ledgerCurrency) {

    public static SubmitResponse of(ExpenseReport report, boolean duplicate) {
        BigDecimal ledgerAmount = report.getRateSnapshot() == null
                ? null : report.getRateSnapshot().getLedgerAmount();
        String ledgerCurrency = report.getRateSnapshot() == null
                ? null : report.getRateSnapshot().getToCurrency();
        return new SubmitResponse(report.getId(), report.getStatus().name(), duplicate,
                ledgerAmount, ledgerCurrency);
    }
}
