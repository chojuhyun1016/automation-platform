package com.riman.automation.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalApiExceptionTest {

    @Test
    @DisplayName("errorCode가 EXTERNAL_API_ERROR로 고정되고 메시지에 apiName, statusCode가 포함된다")
    void constructor_setsFieldsAndFormatsMessage() {
        var ex = new ExternalApiException("Slack", 400, "invalid_auth");

        assertThat(ex.getErrorCode()).isEqualTo("EXTERNAL_API_ERROR");
        assertThat(ex.getApiName()).isEqualTo("Slack");
        assertThat(ex.getStatusCode()).isEqualTo(400);
        assertThat(ex.getMessage()).isEqualTo("[Slack] status=400: invalid_auth");
    }

    @Test
    @DisplayName("cause가 포함된 생성자에서 cause가 전파된다")
    void constructorWithCause_propagatesCause() {
        var cause = new RuntimeException("원인");
        var ex = new ExternalApiException("Jira", 500, "서버 오류", cause);

        assertThat(ex.getErrorCode()).isEqualTo("EXTERNAL_API_ERROR");
        assertThat(ex.getApiName()).isEqualTo("Jira");
        assertThat(ex.getStatusCode()).isEqualTo(500);
        assertThat(ex.getMessage()).isEqualTo("[Jira] status=500: 서버 오류");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("Slack ok:false 케이스 — HTTP 200이지만 오류")
    void slackOkFalse_http200ButError() {
        var ex = new ExternalApiException("Slack", 200, "error=channel_not_found");

        assertThat(ex.getStatusCode()).isEqualTo(200);
        assertThat(ex.getMessage()).contains("channel_not_found");
    }
}
