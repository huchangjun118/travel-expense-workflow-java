package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseType;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.Money;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ExpenseService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 报销单 REST 接口。 */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    public SubmitResponse submit(@RequestHeader("X-User-Id") String userId,
                                 @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                 @RequestBody SubmitRequest body) {
        var result = expenseService.submit(userId, idempotencyKey, toMoney(body),
                toType(body), body.getDepartment(), body.getDescription());
        return SubmitResponse.of(result.report(), result.duplicate());
    }

    @PostMapping("/drafts")
    public ExpenseReport createDraft(@RequestHeader("X-User-Id") String userId,
                                     @RequestBody SubmitRequest body) {
        return expenseService.createDraft(userId, toMoney(body),
                toType(body), body.getDepartment(), body.getDescription());
    }

    @PostMapping("/drafts/{id}/submit")
    public SubmitResponse submitDraft(@RequestHeader("X-User-Id") String userId,
                                      @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                      @PathVariable String id) {
        var result = expenseService.submitDraft(userId, idempotencyKey, id);
        return SubmitResponse.of(result.report(), result.duplicate());
    }

    @PostMapping("/{id}/approve")
    public ExpenseReport approve(@RequestHeader("X-User-Id") String userId,
                                 @RequestHeader("X-User-Role") String role,
                                 @PathVariable String id,
                                 @RequestBody(required = false) Map<String, String> body) {
        return expenseService.approve(userId, role, id, comment(body));
    }

    @PostMapping("/{id}/reject")
    public ExpenseReport reject(@RequestHeader("X-User-Id") String userId,
                                @RequestHeader("X-User-Role") String role,
                                @PathVariable String id,
                                @RequestBody(required = false) Map<String, String> body) {
        return expenseService.reject(userId, role, id, comment(body));
    }

    @PostMapping("/{id}/withdraw")
    public ExpenseReport withdraw(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        return expenseService.withdraw(userId, id);
    }

    @PostMapping("/{id}/resubmit")
    public SubmitResponse resubmit(@RequestHeader("X-User-Id") String userId,
                                   @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                   @PathVariable String id) {
        var result = expenseService.resubmit(userId, idempotencyKey, id);
        return SubmitResponse.of(result.report(), result.duplicate());
    }

    @GetMapping("/{id}")
    public ExpenseReport get(@PathVariable String id) {
        return expenseService.get(id);
    }

    private static Money toMoney(SubmitRequest body) {
        return new Money(body.getAmount(), body.getCurrency());
    }

    private static ExpenseType toType(SubmitRequest body) {
        try {
            return ExpenseType.valueOf(body.getExpenseType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "非法费用类型: " + body.getExpenseType());
        }
    }

    private static String comment(Map<String, String> body) {
        return body == null ? null : body.get("comment");
    }
}
