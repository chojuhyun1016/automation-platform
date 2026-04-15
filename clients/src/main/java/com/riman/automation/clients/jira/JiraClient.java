package com.riman.automation.clients.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.clients.http.BaseHttpClient;
import com.riman.automation.clients.http.ApiResponse;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Jira Cloud REST API v3 클라이언트.
 * HTTP 요청 전송과 JSON 응답 반환만 담당하며, JQL 구성과 파싱은 상위 계층이 담당한다.
 *
 * Jira Cloud는 GET /rest/api/3/search(HTTP 410 삭제)를 대체하여
 * POST /rest/api/3/search/jql(request body 방식)을 사용한다.
 */
@Slf4j
public class JiraClient extends BaseHttpClient {

  private static final ObjectMapper OM = new ObjectMapper();

  private final String baseUrl;
  private final TokenProvider token;

  public JiraClient(String baseUrl, TokenProvider token) {
    super("Jira");
    this.baseUrl = baseUrl;
    this.token = token;
    log.info("[JiraClient] initialized: baseUrl={}", baseUrl);
  }

  /**
   * JQL로 이슈 전체를 수집한다. nextPageToken 페이지네이션으로 건수 제한 없이 100건씩 반복 호출한다.
   *
   * 구 API(GET /rest/api/3/search)는 Jira Cloud에서 삭제됨 (HTTP 410).
   * 신규 API는 nextPageToken 방식이며, startAt을 body에 포함하면 400 오류가 발생한다.
   *
   * @param jql JQL 쿼리 문자열
   * @param fields 반환할 필드 목록 (쉼표 구분 문자열)
   * @param maxResults 하위 호환 유지용 (실제 무시)
   * @return issues 전체 배열이 담긴 JsonNode (합산 결과)
   */
  public JsonNode search(String jql, String fields, int maxResults) {
    String[] fieldArray = Arrays.stream(fields.split(","))
        .map(String::trim)
        .filter(f -> !f.isEmpty())
        .toArray(String[]::new);

    ArrayNode allIssues = OM.createArrayNode();
    String nextPageToken = null;
    int pageCount = 0;

    log.info("[JiraClient] JQL: {}", jql);

    do {
      ObjectNode body = OM.createObjectNode();
      body.put("jql", jql);
      body.put("maxResults", 100);
      if (nextPageToken != null) {
        body.put("nextPageToken", nextPageToken);
      }
      ArrayNode fieldsArray = body.putArray("fields");
      for (String f : fieldArray) {
        fieldsArray.add(f);
      }

      String jsonBody;
      try {
        jsonBody = OM.writeValueAsString(body);
      } catch (Exception e) {
        throw new ExternalApiClientException(apiName, "request body 직렬화 실패", e);
      }

      String url = baseUrl + "/rest/api/3/search/jql";
      ApiResponse response = post(url, defaultHeaders(), jsonBody);
      requireSuccess(response, "search");
      JsonNode page = parse(response.getBody());
      pageCount++;

      JsonNode issues = page.path("issues");
      if (issues.isArray()) {
        issues.forEach(allIssues::add);
      }

      JsonNode tokenNode = page.path("nextPageToken");
      nextPageToken = (tokenNode.isMissingNode() || tokenNode.isNull())
          ? null : tokenNode.asText(null);

    } while (nextPageToken != null);

    log.info("[JiraClient] 전체 수집: {}건 ({}페이지)", allIssues.size(), pageCount);

    ObjectNode result = OM.createObjectNode();
    result.set("issues", allIssues);
    return result;
  }

  /**
   * 이슈 키로 단일 이슈를 조회한다.
   *
   * @param issueKey 이슈 키 (예: CCE-123)
   * @param fields 반환할 필드 목록 (쉼표 구분)
   * @return Jira issue JsonNode
   */
  public JsonNode getIssue(String issueKey, String fields) {
    String url = baseUrl + "/rest/api/3/issue/" + issueKey
        + "?fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);

    log.info("[JiraClient] getIssue: {}", issueKey);
    ApiResponse response = get(url, defaultHeaders());
    requireSuccess(response, "getIssue");
    return parse(response.getBody());
  }

  private Map<String, String> defaultHeaders() {
    return Map.of(
        "Authorization", token.toBasicHeader(),
        "Accept", "application/json",
        "Content-Type", "application/json"
    );
  }

  private JsonNode parse(String body) {
    try {
      return OM.readTree(body);
    } catch (Exception e) {
      throw new ExternalApiClientException(apiName, "JSON 파싱 실패", e);
    }
  }
}
