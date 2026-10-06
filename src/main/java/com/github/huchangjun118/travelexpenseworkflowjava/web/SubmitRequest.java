package com.github.huchangjun118.travelexpenseworkflowjava.web;

import java.math.BigDecimal;

public record SubmitRequest(String idempotencyKey, String department, String expenseType,
                            BigDecimal amount, String currency, Long budgetAccountId) {
}
