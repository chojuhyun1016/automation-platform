package com.riman.automation.clients.confluence;

import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.exception.ExternalApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfluenceClient의 executeWithRetry 지수 백오프 로직 테스트.
 *
 * <p>실제 HTTP 호출 없이 retry 판정 + 재시도 횟수만 검증한다.
 */
class ConfluenceClientRetryTest {

    private final ConfluenceClient client = new ConfluenceClient(
            "https://test.atlassian.net", "TEST",
            new StubTokenProvider());

    // =========================================================================
    // 성공 케이스
    // =========================================================================

    @Test
    @DisplayName("첫 시도에 성공하면 결과를 즉시 반환한다")
    void executeWithRetry_firstSuccess_returnsImmediately() {
        String result = client.executeWithRetry("test", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("일시적 오류 후 성공하면 결과를 반환한다")
    void executeWithRetry_transientFailureThenSuccess_returnsResult() {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = client.executeWithRetry("test", () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new ExternalApiClientException("Confluence", "연결 타임아웃");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    // =========================================================================
    // 재시도 대상 판정
    // =========================================================================

    @Test
    @DisplayName("ExternalApiClientException은 재시도한다 (연결/타임아웃)")
    void executeWithRetry_clientException_retries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiClientException("Confluence", "연결 실패");
                })
        ).isInstanceOf(ExternalApiClientException.class);

        assertThat(attempts.get()).isEqualTo(4); // 1 + 3 retries
    }

    @Test
    @DisplayName("HTTP 429 (Rate Limit)는 재시도한다")
    void executeWithRetry_rateLimited_retries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiException("Confluence", 429, "Too Many Requests");
                })
        ).isInstanceOf(ExternalApiException.class);

        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("HTTP 500은 재시도한다")
    void executeWithRetry_serverError_retries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiException("Confluence", 500, "Internal Server Error");
                })
        ).isInstanceOf(ExternalApiException.class);

        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("HTTP 502/503도 재시도한다")
    void executeWithRetry_badGateway_retries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiException("Confluence", 502, "Bad Gateway");
                })
        ).isInstanceOf(ExternalApiException.class);

        assertThat(attempts.get()).isEqualTo(4);
    }

    // =========================================================================
    // 재시도하지 않는 케이스
    // =========================================================================

    @Test
    @DisplayName("HTTP 400 (Bad Request)는 재시도하지 않는다")
    void executeWithRetry_badRequest_noRetry() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiException("Confluence", 400, "Bad Request");
                })
        ).isInstanceOf(ExternalApiException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 404는 재시도하지 않는다")
    void executeWithRetry_notFound_noRetry() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiException("Confluence", 404, "Not Found");
                })
        ).isInstanceOf(ExternalApiException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("일반 RuntimeException은 재시도하지 않는다")
    void executeWithRetry_genericException_noRetry() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("unexpected");
                })
        ).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    // =========================================================================
    // void 반환 래퍼
    // =========================================================================

    @Test
    @DisplayName("Runnable 래퍼도 재시도가 동작한다")
    void executeWithRetry_runnable_retries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
                client.executeWithRetry("test", (Runnable) () -> {
                    attempts.incrementAndGet();
                    throw new ExternalApiClientException("Confluence", "타임아웃");
                })
        ).isInstanceOf(ExternalApiClientException.class);

        assertThat(attempts.get()).isEqualTo(4);
    }

    // =========================================================================
    // 스텁
    // =========================================================================

    private static class StubTokenProvider implements com.riman.automation.common.auth.TokenProvider {
        @Override
        public String getToken() {
            return "test-token";
        }
    }
}
