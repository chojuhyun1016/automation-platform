package com.riman.automation.common.exception;

/**
 * 외부 API 서버로부터 실패 응답을 받은 경우
 *
 * <p>HTTP 응답은 수신했으나 상태 코드가 2xx가 아니거나,
 * Slack처럼 HTTP 200이어도 {@code ok:false}인 경우에 사용한다.
 *
 * <p>{@code statusCode}는 항상 실제 HTTP 응답 코드이다.
 * HTTP 통신 자체 실패(연결 오류, 타임아웃)나 직렬화/파싱 오류는
 * {@link ExternalApiClientException}을 사용한다.
 *
 * <pre>
 * 사용 예:
 *   HTTP 4xx, 5xx 응답       → ExternalApiException(apiName, statusCode, message)
 *   Slack ok:false           → ExternalApiException(apiName, 200, "error=" + error)
 * </pre>
 */
public class ExternalApiException extends AutomationException {

    private final String apiName;
    private final int statusCode;

    /**
     * @param apiName    API 식별자 (예: "Jira", "Slack", "GoogleCalendar", "Anthropic")
     * @param statusCode 실제 HTTP 응답 상태 코드
     * @param message    오류 상세 메시지
     */
    public ExternalApiException(String apiName, int statusCode, String message) {
        super("EXTERNAL_API_ERROR",
                String.format("[%s] status=%d: %s", apiName, statusCode, message));
        this.apiName = apiName;
        this.statusCode = statusCode;
    }

    public ExternalApiException(String apiName, int statusCode, String message, Throwable cause) {
        super("EXTERNAL_API_ERROR",
                String.format("[%s] status=%d: %s", apiName, statusCode, message), cause);
        this.apiName = apiName;
        this.statusCode = statusCode;
    }

    public String getApiName() {
        return apiName;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
