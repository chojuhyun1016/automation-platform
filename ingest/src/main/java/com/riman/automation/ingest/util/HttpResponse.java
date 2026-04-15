package com.riman.automation.ingest.util;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

/**
 * APIGatewayProxyResponseEvent 생성 유틸리티.
 * Lambda HTTP 응답을 일관되게 생성하기 위한 static 헬퍼이다.
 */
public final class HttpResponse {

  private static final Map<String, String> JSON_HEADER =
      Map.of("Content-Type", "application/json");

  private HttpResponse() {
  }

  /**
   * 200 OK — 빈 body 또는 단순 문자열을 반환한다.
   */
  public static APIGatewayProxyResponseEvent ok(String body) {
    return build(200, body);
  }

  /**
   * 200 OK — Jira 이벤트 수신 완료 응답.
   * X-Request-Id 헤더에 requestId 를 포함하여 추적을 돕는다.
   */
  public static APIGatewayProxyResponseEvent jiraAccepted(
      ObjectMapper objectMapper, String eventId, String messageId, String requestId) {
    try {
      ObjectNode body = JsonNodeFactory.instance.objectNode()
          .put("status", "accepted")
          .put("eventId", eventId)
          .put("messageId", messageId)
          .put("timestamp", Instant.now().toString());

      APIGatewayProxyResponseEvent r = new APIGatewayProxyResponseEvent();
      r.setStatusCode(200);
      r.setHeaders(Map.of(
          "Content-Type", "application/json",
          "X-Request-Id", requestId
      ));
      r.setBody(objectMapper.writeValueAsString(body));
      return r;
    } catch (Exception e) {
      return internalError();
    }
  }

  /**
   * 200 OK — Slack Modal 유효성 오류 응답.
   * Slack errors 응답의 키는 반드시 block_id 여야 하며 action_id 를 넘기면 오류가 표시되지 않는다.
   *
   * @param blockId 오류를 표시할 블록의 block_id
   * @param message 오류 메시지
   */
  public static APIGatewayProxyResponseEvent modalError(String blockId, String message) {
    ObjectNode errors = JsonNodeFactory.instance.objectNode().put(blockId, message);
    ObjectNode root = JsonNodeFactory.instance.objectNode()
        .put("response_action", "errors");
    root.set("errors", errors);
    return build(200, root.toString());
  }

  /**
   * 200 OK — Slack Modal 결과 화면 교체 (기본 타이틀 "결과").
   */
  public static APIGatewayProxyResponseEvent modalResult(boolean success, String message) {
    return modalResult(success, message, "결과");
  }

  /**
   * 200 OK — Slack Modal 결과 화면 교체 (view_submission 전용, modalTitle 지정).
   * submit 버튼 없이 '닫기'만 표시되며 block_actions 응답에는 동작하지 않는다.
   */
  public static APIGatewayProxyResponseEvent modalResult(
      boolean success, String message, String modalTitle) {
    String icon = success ? "✅" : "❌";

    ObjectNode text = JsonNodeFactory.instance.objectNode()
        .put("type", "mrkdwn")
        .put("text", icon + "  " + message);

    ObjectNode section = JsonNodeFactory.instance.objectNode()
        .put("type", "section");
    section.set("text", text);

    com.fasterxml.jackson.databind.node.ArrayNode blocks =
        JsonNodeFactory.instance.arrayNode();
    blocks.add(section);

    // Slack title 은 최대 24자 제한이므로 초과 시 잘라낸다.
    String title = modalTitle != null && !modalTitle.isBlank() ? modalTitle : "결과";
    if (title.length() > 24) title = title.substring(0, 24);

    ObjectNode view = JsonNodeFactory.instance.objectNode()
        .put("type", "modal");
    view.set("title", plainTextNode(title));
    view.set("close", plainTextNode("닫기"));
    view.set("blocks", blocks);

    ObjectNode root = JsonNodeFactory.instance.objectNode()
        .put("response_action", "update");
    root.set("view", view);

    return build(200, root.toString());
  }

  private static ObjectNode plainTextNode(String text) {
    return JsonNodeFactory.instance.objectNode()
        .put("type", "plain_text")
        .put("text", text)
        .put("emoji", true);
  }

  /**
   * 400 Bad Request.
   */
  public static APIGatewayProxyResponseEvent badRequest(String message) {
    return build(400, "{\"error\":\"" + message + "\"}");
  }

  /**
   * 401 Unauthorized.
   */
  public static APIGatewayProxyResponseEvent unauthorized() {
    return build(401, "{\"error\":\"Unauthorized\"}");
  }

  /**
   * 404 Not Found.
   */
  public static APIGatewayProxyResponseEvent notFound(String path) {
    return build(404, "{\"error\":\"Unknown path: " + path + "\"}");
  }

  /**
   * 500 Internal Server Error.
   */
  public static APIGatewayProxyResponseEvent internalError() {
    return build(500, "{\"error\":\"Internal error\"}");
  }

  private static APIGatewayProxyResponseEvent build(int status, String body) {
    APIGatewayProxyResponseEvent r = new APIGatewayProxyResponseEvent();
    r.setStatusCode(status);
    r.setHeaders(JSON_HEADER);
    r.setBody(body);
    return r;
  }
}
