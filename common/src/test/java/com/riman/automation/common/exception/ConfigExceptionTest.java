package com.riman.automation.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigExceptionTest {

    @Test
    @DisplayName("errorCode가 CONFIG_ERROR로 고정된다")
    void errorCode_isConfigError() {
        var ex = new ConfigException("환경변수 미설정");

        assertThat(ex.getErrorCode()).isEqualTo("CONFIG_ERROR");
        assertThat(ex.getMessage()).isEqualTo("환경변수 미설정");
    }

    @Test
    @DisplayName("cause가 포함된 생성자에서 cause가 전파된다")
    void constructorWithCause_propagatesCause() {
        var cause = new RuntimeException("원인");
        var ex = new ConfigException("S3 로드 실패", cause);

        assertThat(ex.getErrorCode()).isEqualTo("CONFIG_ERROR");
        assertThat(ex.getMessage()).isEqualTo("S3 로드 실패");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("AutomationException을 상속한다")
    void extendsAutomationException() {
        var ex = new ConfigException("msg");

        assertThat(ex).isInstanceOf(AutomationException.class);
    }
}
