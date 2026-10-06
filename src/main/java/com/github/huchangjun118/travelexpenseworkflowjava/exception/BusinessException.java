package com.github.huchangjun118.travelexpenseworkflowjava.exception;

/** 业务异常，携带可区分原因。 */
public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
