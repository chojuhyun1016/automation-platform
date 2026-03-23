package com.riman.automation.common.exception;

/**
 * 자동화 플랫폼 최상위 Unchecked 예외
 */
public class AutomationException extends RuntimeException {

    private final String errorCode;

    public AutomationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AutomationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
