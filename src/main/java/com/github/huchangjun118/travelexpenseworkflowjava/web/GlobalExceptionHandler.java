package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误响应，错误码可区分。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handle(BusinessException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case REPORT_NOT_FOUND, BUDGET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_STATE_TRANSITION, BUDGET_EXCEEDED, RATE_MISSING, RATE_EXPIRED,
                 VALIDATION_ERROR -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.getCode().name(), "message", ex.getMessage()));
    }
}
