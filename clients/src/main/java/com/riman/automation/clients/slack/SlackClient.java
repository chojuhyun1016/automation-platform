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
 * Slack Web API 클라이언트
 *
 * <p><b>책임:</b> HTTP 요청 전송 + Slack ok 필드 검증만.
 * 메시지 내용, 포맷, 라우팅 — 모두 상위 계층(scheduler 등)이 결정한다.
 *
 * <p>지원 API:
 * <ul>
 *   <li>{@link #postMessage(String)} — chat.postMessage</li>
 *   <li>{@link #openView(String)}    — views.open (Modal)</li>
 *   <li>{@link #openDm(String)}      — conversations.open (DM 채널 열기)</li>
 * </ul>
 *
 * <p>TokenProvider는 상위 계층에서 주입 (EnvTokenProvider 등).
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

    // =========================================================================
    // chat.postMessage
    // =========================================================================

    /**
     * 채널/DM에 메시지 전송
     *
     * @param jsonPayload channel, text, blocks 를 포함한 Slack Block Kit JSON
     * @return message timestamp (ts)
     */
    public String postMessage(String jsonPayload) {
        ApiResponse response = post(BASE + "/chat.postMessage", authJsonHeaders(), jsonPayload);
        requireSuccess(response, "chat.postMessage");
        assertSlackOk(response, "chat.postMessage");
        return parseField(response.getBody(), "ts");
    }

    // =========================================================================
    // views.open
    // =========================================================================

    /**
     * Modal 팝업 표시
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
     * 현재 표시 중인 Modal을 새 view로 교체
     *
     * <p>block_actions에서 HTTP 응답으로 모달을 바꿀 수 없으므로
     * views.update API를 직접 호출해 결과 화면으로 교체한다.
     *
     * @param jsonPayload view_id + view JSON
     */
    public void updateView(String jsonPayload) {
        ApiResponse response = post(BASE + "/views.update", authJsonHeaders(), jsonPayload);
        requireSuccess(response, "views.update");
        assertSlackOk(response, "views.update");
        log.info("[SlackClient] views.update 완료");
    }

    // =========================================================================
    // conversations.open
    // =========================================================================

    /**
     * DM 채널 열기
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


    // =========================================================================
    // users.info
    // =========================================================================

    /**
     * Slack users.info API — 사용자 실제 이름(real_name) 조회
     *
     * <p>get()은 protected이므로 SlackClient 내부에서 호출한다.
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

    // =========================================================================
    // 내부
    // =========================================================================

    private Map<String, String> authJsonHeaders() {
        return Map.of(
                "Content-Type", "application/json; charset=UTF-8",
                "Authorization", token.toBearerHeader()
        );
    }

    /**
     * Slack API는 HTTP 200이어도 ok:false일 수 있음 → ExternalApiException
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
