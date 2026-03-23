package com.riman.automation.common.exception;

/**
 * 설정(환경변수, S3 config) 오류
 */
public class ConfigException extends AutomationException {

    public ConfigException(String message) {
        super("CONFIG_ERROR", message);
    }

    public ConfigException(String message, Throwable cause) {
        super("CONFIG_ERROR", message, cause);
    }
}
