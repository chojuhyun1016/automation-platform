package com.riman.automation.clients.http;

import com.riman.automation.common.exception.ExternalApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseHttpClientTest {

    /**
     * requireSuccess()를 테스트하기 위한 최소 구현체
     */
    private static class TestHttpClient extends BaseHttpClient {
        TestHttpClient() {
            super("TestApi");
        }

        void callRequireSuccess(ApiResponse response, String operation) {
            requireSuccess(response, operation);
        }
    }

    private final TestHttpClient client = new TestHttpClient();

    @Test
    @DisplayName("requireSuccess — 2xx 응답이면 예외 없이 통과")
    void requireSuccess_2xx_noException() {
        var response = new ApiResponse(200, "{\"ok\":true}");

        assertThatCode(() -> client.callRequireSuccess(response, "postMessage"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requireSuccess — 201 Created도 통과")
    void requireSuccess_201_noException() {
        var response = new ApiResponse(201, "");

        assertThatCode(() -> client.callRequireSuccess(response, "create"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requireSuccess — 4xx 응답이면 ExternalApiException")
    void requireSuccess_4xx_throwsExternalApiException() {
        var response = new ApiResponse(400, "Bad Request");

        assertThatThrownBy(() -> client.callRequireSuccess(response, "search"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("TestApi")
                .hasMessageContaining("400")
                .hasMessageContaining("search 실패");
    }

    @Test
    @DisplayName("requireSuccess — 5xx 응답이면 ExternalApiException")
    void requireSuccess_5xx_throwsExternalApiException() {
        var response = new ApiResponse(500, "Internal Server Error");

        assertThatThrownBy(() -> client.callRequireSuccess(response, "update"))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("TestApi")
                .hasMessageContaining("500")
                .hasMessageContaining("update 실패")
                .hasMessageContaining("Internal Server Error");
    }

    @Test
    @DisplayName("requireSuccess — 예외의 errorCode가 EXTERNAL_API_ERROR")
    void requireSuccess_errorCodeIsCorrect() {
        var response = new ApiResponse(403, "Forbidden");

        assertThatThrownBy(() -> client.callRequireSuccess(response, "delete"))
                .isInstanceOf(ExternalApiException.class)
                .extracting("errorCode")
                .isEqualTo("EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("requireSuccess — 예외 메시지에 response body 포함")
    void requireSuccess_exceptionContainsBody() {
        var response = new ApiResponse(404, "Not Found");

        assertThatThrownBy(() -> client.callRequireSuccess(response, "getIssue"))
                .hasMessageContaining("Not Found");
    }
}
