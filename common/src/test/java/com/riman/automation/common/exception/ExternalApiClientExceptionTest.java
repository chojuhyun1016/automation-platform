package com.riman.automation.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalApiClientExceptionTest {

    @Test
    @DisplayName("errorCode가 EXTERNAL_API_CLIENT_ERROR로 고정되고 메시지에 apiName이 포함된다")
    void constructor_setsFieldsAndFormatsMessage() {
        var ex = new ExternalApiClientException("GoogleCalendar", "연결 타임아웃");

        assertThat(ex.getErrorCode()).isEqualTo("EXTERNAL_API_CLIENT_ERROR");
        assertThat(ex.getApiName()).isEqualTo("GoogleCalendar");
        assertThat(ex.getMessage()).isEqualTo("[GoogleCalendar] 연결 타임아웃");
    }

    @Test
    @DisplayName("cause가 포함된 생성자에서 cause가 전파된다")
    void constructorWithCause_propagatesCause() {
        var cause = new java.net.SocketTimeoutException("Read timed out");
        var ex = new ExternalApiClientException("Anthropic", "GET 실패", cause);

        assertThat(ex.getErrorCode()).isEqualTo("EXTERNAL_API_CLIENT_ERROR");
        assertThat(ex.getApiName()).isEqualTo("Anthropic");
        assertThat(ex.getMessage()).isEqualTo("[Anthropic] GET 실패");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("AutomationException을 상속한다")
    void extendsAutomationException() {
        var ex = new ExternalApiClientException("Slack", "파싱 실패");

        assertThat(ex).isInstanceOf(AutomationException.class);
    }
}
