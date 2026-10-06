package com.github.huchangjun118.travelexpenseworkflowjava.web;

import com.github.huchangjun118.travelexpenseworkflowjava.service.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.service.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case ErrorCodes.BUDGET_EXCEEDED, ErrorCodes.FX_RATE_UNAVAILABLE,
                 ErrorCodes.ILLEGAL_STATE_TRANSITION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }
}
