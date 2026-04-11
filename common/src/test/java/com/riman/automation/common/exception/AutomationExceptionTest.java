package com.riman.automation.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationExceptionTest {

    @Test
    @DisplayName("errorCode와 message가 정확히 설정된다")
    void constructor_setsErrorCodeAndMessage() {
        var ex = new AutomationException("TEST_ERROR", "테스트 메시지");

        assertThat(ex.getErrorCode()).isEqualTo("TEST_ERROR");
        assertThat(ex.getMessage()).isEqualTo("테스트 메시지");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("cause가 포함된 생성자에서 cause가 전파된다")
    void constructorWithCause_propagatesCause() {
        var cause = new IllegalStateException("원인");
        var ex = new AutomationException("ERR", "메시지", cause);

        assertThat(ex.getErrorCode()).isEqualTo("ERR");
        assertThat(ex.getMessage()).isEqualTo("메시지");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("RuntimeException을 상속한다")
    void isRuntimeException() {
        var ex = new AutomationException("CODE", "msg");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
