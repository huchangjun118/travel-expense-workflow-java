package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;

public record SubmitResponse(ExpenseReport report, boolean duplicated) {
}
