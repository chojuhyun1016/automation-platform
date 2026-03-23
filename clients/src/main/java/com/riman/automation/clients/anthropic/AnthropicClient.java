package com.riman.automation.clients.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.clients.http.BaseHttpClient;
import com.riman.automation.clients.http.ApiResponse;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Anthropic Claude API 클라이언트
 *
 * <p><b>책임:</b> HTTP 요청 전송 + 텍스트 응답 반환만.
 * 프롬프트 내용, 어떤 규칙 파일을 쓸지 — 상위 계층(scheduler service)이 결정.
 *
 * <p>보고서 자연어 후처리 흐름:
 * <pre>
 *   구조화된 보고서 텍스트
 *       ↓
 *   AnthropicClient.complete(systemPrompt, reportText)
 *       ↓  (system에는 DAILY_REPORT_RULES.md 내용 주입)
 *   자연스럽게 다듬어진 Slack mrkdwn 텍스트
 * </pre>
 *
 * <p>TokenProvider는 상위 계층에서 주입 (EnvTokenProvider("ANTHROPIC_API_KEY")).
 */
@Slf4j
public class AnthropicClient extends BaseHttpClient {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_TOKENS = 4096;

    private static final ObjectMapper OM = new ObjectMapper();

    private final TokenProvider token;

    public AnthropicClient(TokenProvider token) {
        super("Anthropic");
        this.token = token;
        log.info("[AnthropicClient] initialized");
    }

    // =========================================================================
    // 단일 텍스트 완성
    // =========================================================================

    /**
     * Claude에게 메시지를 보내고 텍스트 응답을 받는다.
     *
     * @param systemPrompt 시스템 프롬프트 (보고서 규칙, 포맷 지침 등)
     * @param userMessage  사용자 메시지 (보고서 원본 데이터 등)
     * @return Claude의 텍스트 응답
     */
    public String complete(String systemPrompt, String userMessage) {
        return complete(systemPrompt, userMessage, DEFAULT_MODEL, DEFAULT_TOKENS);
    }

    public String complete(String systemPrompt, String userMessage, String model, int maxTokens) {
        String payload = buildPayload(systemPrompt, userMessage, model, maxTokens);
        log.info("[AnthropicClient] complete: model={}, maxTokens={}", model, maxTokens);

        ApiResponse response = post(URL, defaultHeaders(), payload);
        requireSuccess(response, "messages");
        return extractText(response.getBody());
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private String buildPayload(String systemPrompt, String userMessage,
                                String model, int maxTokens) {
        try {
            ObjectNode root = OM.createObjectNode()
                    .put("model", model)
                    .put("max_tokens", maxTokens);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                root.put("system", systemPrompt);
            }

            ArrayNode messages = OM.createArrayNode();
            ObjectNode userMsg = OM.createObjectNode().put("role", "user");
            ArrayNode content = OM.createArrayNode();
            content.add(OM.createObjectNode().put("type", "text").put("text", userMessage));
            userMsg.set("content", content);
            messages.add(userMsg);
            root.set("messages", messages);

            return OM.writeValueAsString(root);
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName, "페이로드 직렬화 실패", e);
        }
    }

    private String extractText(String body) {
        try {
            JsonNode root = OM.readTree(body);
            return root.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            throw new ExternalApiClientException(apiName, "응답 텍스트 파싱 실패", e);
        }
    }

    private Map<String, String> defaultHeaders() {
        return Map.of(
                "x-api-key", token.getToken(),
                "anthropic-version", API_VERSION,
                "Content-Type", "application/json"
        );
    }
}
