package com.riman.automation.common.exception;

/**
 * 외부 API 통신 과정에서 발생한 클라이언트 측 오류.
 * HTTP 응답을 받기 전 또는 응답 처리 중에 발생하는 오류에 사용한다.
 *
 * 사용 예:
 *   연결 실패, 타임아웃       -> ExternalApiClientException(apiName, "GET 실패: " + url, e)
 *   요청 페이로드 직렬화 실패 -> ExternalApiClientException(apiName, "페이로드 직렬화 실패", e)
 *   응답 JSON 파싱 실패       -> ExternalApiClientException(apiName, "JSON 파싱 실패", e)
 */
public class ExternalApiClientException extends AutomationException {

  private final String apiName;

  /**
   * @param apiName API 식별자 (예: "Jira", "Slack", "GoogleCalendar", "Anthropic")
   * @param message 오류 상세 메시지
   */
  public ExternalApiClientException(String apiName, String message) {
    super("EXTERNAL_API_CLIENT_ERROR",
        String.format("[%s] %s", apiName, message));
    this.apiName = apiName;
  }

  public ExternalApiClientException(String apiName, String message, Throwable cause) {
    super("EXTERNAL_API_CLIENT_ERROR",
        String.format("[%s] %s", apiName, message), cause);
    this.apiName = apiName;
  }

  public String getApiName() {
    return apiName;
  }
}
