package com.riman.automation.clients.http;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * HTTP API 응답 값 객체
 *
 * <p>clients 계층 전반에서 사용하는 내부 VO.
 * {@link BaseHttpClient}의 {@code get()}, {@code post()} 반환 타입이며,
 * 각 Client(SlackClient, JiraClient 등) 내부에서 상태 코드 검증과 응답 파싱에 사용된다.
 *
 * <p>상위 계층(scheduler 등)에는 이 객체가 노출되지 않는다.
 * 각 Client가 도메인 타입으로 파싱한 결과만 반환한다.
 */
@Getter
@RequiredArgsConstructor
public class ApiResponse {

    private final int statusCode;
    private final String body;

    /**
     * 2xx 응답 여부
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 4xx 클라이언트 오류 여부
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * 5xx 서버 오류 여부
     */
    public boolean isServerError() {
        return statusCode >= 500;
    }

    @Override
    public String toString() {
        int preview = body != null ? Math.min(body.length(), 300) : 0;
        return "ApiResponse{status=" + statusCode
                + ", body=" + (body != null ? body.substring(0, preview) : "null") + "}";
    }
}
