package com.riman.automation.clients.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    // ── isSuccess ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 299})
    @DisplayName("isSuccess — 2xx이면 true")
    void isSuccess_2xx_returnsTrue(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isSuccess()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 300, 400, 500})
    @DisplayName("isSuccess — 2xx 외에는 false")
    void isSuccess_non2xx_returnsFalse(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isSuccess()).isFalse();
    }

    // ── isClientError ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 499})
    @DisplayName("isClientError — 4xx이면 true")
    void isClientError_4xx_returnsTrue(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isClientError()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 399, 500})
    @DisplayName("isClientError — 4xx 외에는 false")
    void isClientError_non4xx_returnsFalse(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isClientError()).isFalse();
    }

    // ── isServerError ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 599, 600})
    @DisplayName("isServerError — 500 이상이면 true (상한 없음)")
    void isServerError_500plus_returnsTrue(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isServerError()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 499})
    @DisplayName("isServerError — 500 미만이면 false")
    void isServerError_below500_returnsFalse(int statusCode) {
        assertThat(new ApiResponse(statusCode, "").isServerError()).isFalse();
    }

    // ── toString ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString — body가 null이면 'null' 표시")
    void toString_nullBody_showsNull() {
        var response = new ApiResponse(200, null);
        assertThat(response.toString()).contains("null");
    }

    @Test
    @DisplayName("toString — body가 300자 초과이면 잘린다")
    void toString_longBody_truncated() {
        String longBody = "x".repeat(500);
        var response = new ApiResponse(200, longBody);
        String str = response.toString();

        // 300자까지만 표시
        assertThat(str).contains("x".repeat(300));
        assertThat(str).doesNotContain("x".repeat(301));
    }
}
