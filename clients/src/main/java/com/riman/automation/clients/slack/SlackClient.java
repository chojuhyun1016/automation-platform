package com.riman.automation.clients.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.http.ApiResponse;
import com.riman.automation.clients.http.BaseHttpClient;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Slack Web API 클라이언트.
 * HTTP 요청 전송과 Slack ok 필드 검증만 담당하며, 메시지 내용과 라우팅은 상위 계층이 결정한다.
 *
 * 지원 API: chat.postMessage, views.open, views.update, conversations.open, users.info
 */
@Slf4j
public class SlackClient extends BaseHttpClient {

  private static final String BASE = "https://slack.com/api";
  private static final ObjectMapper OM = new ObjectMapper();

  private final TokenProvider token;

  public SlackClient(TokenProvider token) {
    super("Slack");
    this.token = token;
    log.info("[SlackClient] initialized");
  }

  /**
   * 채널/DM에 메시지를 전송한다.
   *
   * @param jsonPayload channel, text, blocks를 포함한 Slack Block Kit JSON
   * @return message timestamp (ts)
   */
  public String postMessage(String jsonPayload) {
    ApiResponse response = post(BASE + "/chat.postMessage", authJsonHeaders(), jsonPayload);
    requireSuccess(response, "chat.postMessage");
    assertSlackOk(response, "chat.postMessage");
    return parseField(response.getBody(), "ts");
  }

  /**
   * Modal 팝업을 표시한다.
   *
   * @param jsonPayload trigger_id + view JSON
   */
  public void openView(String jsonPayload) {
    ApiResponse response = post(BASE + "/views.open", authJsonHeaders(), jsonPayload);
    requireSuccess(response, "views.open");
    assertSlackOk(response, "views.open");
    log.info("[SlackClient] views.open 완료");
  }

  /**
   * 현재 표시 중인 Modal을 새 view로 교체한다.
   * block_actions에서 HTTP 응답으로 모달을 바꿀 수 없으므로 views.update API를 직접 호출한다.
   *
   * @param jsonPayload view_id + view JSON
   */
  public void updateView(String jsonPayload) {
    ApiResponse response = post(BASE + "/views.update", authJsonHeaders(), jsonPayload);
    requireSuccess(response, "views.update");
    assertSlackOk(response, "views.update");
    log.info("[SlackClient] views.update 완료");
  }

  /**
   * DM 채널을 연다.
   *
   * @param userId Slack User ID
   * @return DM channel ID (C로 시작)
   */
  public String openDm(String userId) {
    String payload = "{\"users\":\"" + userId + "\"}";
    ApiResponse response = post(BASE + "/conversations.open", authJsonHeaders(), payload);
    requireSuccess(response, "conversations.open");
    assertSlackOk(response, "conversations.open");
    try {
      return OM.readTree(response.getBody()).path("channel").path("id").asText();
    } catch (Exception e) {
      throw new ExternalApiClientException(apiName, "DM channel ID 파싱 실패", e);
    }
  }

  /**
   * 사용자 실제 이름(real_name)을 조회한다.
   *
   * @param userId Slack User ID
   * @return real_name (profile.real_name 우선), 없으면 null
   */
  public String getUserRealName(String userId) {
    try {
      String url = BASE + "/users.info?user=" + userId;
      ApiResponse response = get(url, authJsonHeaders());
      if (!response.isSuccess()) {
        log.warn("[SlackClient] users.info HTTP 실패: userId={}, status={}", userId, response.getStatusCode());
        return null;
      }
      JsonNode root = OM.readTree(response.getBody());
      if (!root.path("ok").asBoolean()) {
        log.warn("[SlackClient] users.info ok=false: userId={}, error={}",
            userId, root.path("error").asText());
        return null;
      }
      JsonNode profile = root.path("user").path("profile");
      String realName = profile.path("real_name").asText("").trim();
      if (realName.isBlank()) {
        realName = root.path("user").path("real_name").asText("").trim();
      }
      return realName.isBlank() ? null : realName;
    } catch (Exception e) {
      log.warn("[SlackClient] users.info 오류: userId={}, msg={}", userId, e.getMessage());
      return null;
    }
  }

  private Map<String, String> authJsonHeaders() {
    return Map.of(
        "Content-Type", "application/json; charset=UTF-8",
        "Authorization", token.toBearerHeader()
    );
  }

  /**
   * Slack API는 HTTP 200이어도 ok:false일 수 있으므로 별도 검증한다.
   */
  private void assertSlackOk(ApiResponse response, String operation) {
    try {
      JsonNode node = OM.readTree(response.getBody());
      if (!node.path("ok").asBoolean()) {
        String error = node.path("error").asText("unknown");
        throw new ExternalApiException(apiName, response.getStatusCode(),
            operation + " error=" + error);
      }
    } catch (ExternalApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ExternalApiClientException(apiName, "응답 파싱 실패", e);
    }
  }

  private String parseField(String body, String field) {
    try {
      return OM.readTree(body).path(field).asText("");
    } catch (Exception e) {
      return "";
    }
  }
}
