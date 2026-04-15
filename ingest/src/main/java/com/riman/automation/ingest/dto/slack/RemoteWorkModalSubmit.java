package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 재택근무 Modal Submit 페이로드 파싱 결과 VO.
 * 모달은 날짜(block_date)와 신청/취소 구분(block_action_type) 2개 필드로 구성된다.
 */
@Getter
public class RemoteWorkModalSubmit {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String userName;
  private final String date;
  private final String action;

  private RemoteWorkModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    // private_metadata 형식: "userId|userName".
    String meta = payload.path("view").path("private_metadata").asText("");
    String rawName = payload.path("user").path("username").asText("");
    this.userName = meta.contains("|") ? meta.split("\\|", 2)[1] : rawName;

    this.date = payload.path("view").path("state").path("values")
        .path("block_date").path("action_date")
        .path("selected_date").asText("");

    this.action = payload.path("view").path("state").path("values")
        .path("block_action_type").path("action_type")
        .path("selected_option").path("value").asText("");
  }

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
  public static RemoteWorkModalSubmit parse(String urlEncodedBody) throws Exception {
    String decoded = URLDecoder.decode(
        urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
    return new RemoteWorkModalSubmit(objectMapper.readTree(decoded));
  }

  public boolean isViewSubmission() {
    return "view_submission".equals(type);
  }

  public boolean isValidAction() {
    return "apply".equals(action) || "cancel".equals(action);
  }
}
