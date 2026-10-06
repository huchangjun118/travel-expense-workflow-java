package com.github.huchangjun118.travelexpenseworkflowjava.service;

/**
 * 业务异常，携带可区分的错误码。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
