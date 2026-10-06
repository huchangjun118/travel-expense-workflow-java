package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExpenseService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    public SubmitResponse submit(@RequestHeader("X-User-Id") String userId,
                                 @RequestBody SubmitRequest request) {
        ExpenseService.SubmitResult result = expenseService.submit(
                request.idempotencyKey(), userId, request.department(), request.expenseType(),
                request.amount(), request.currency(), request.budgetAccountId());
        return new SubmitResponse(result.report(), result.duplicated());
    }

    @GetMapping("/{id}")
    public ExpenseReport get(@PathVariable Long id) {
        return expenseService.get(id);
    }

    @PostMapping("/{id}/approve")
    public ExpenseReport approve(@PathVariable Long id,
                                 @RequestHeader("X-User-Id") String userId,
                                 @RequestHeader("X-User-Role") String role,
                                 @RequestBody(required = false) ApprovalRequest request) {
        return expenseService.approve(id, userId, role, request == null ? null : request.comment());
    }

    @PostMapping("/{id}/reject")
    public ExpenseReport reject(@PathVariable Long id,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("X-User-Role") String role,
                                @RequestBody(required = false) ApprovalRequest request) {
        return expenseService.reject(id, userId, role, request == null ? null : request.comment());
    }

    @PostMapping("/{id}/withdraw")
    public ExpenseReport withdraw(@PathVariable Long id,
                                  @RequestHeader("X-User-Id") String userId) {
        return expenseService.withdraw(id, userId);
    }

    @PostMapping("/{id}/resubmit")
    public ExpenseReport resubmit(@PathVariable Long id,
                                  @RequestHeader("X-User-Id") String userId) {
        return expenseService.resubmit(id, userId);
    }
}
