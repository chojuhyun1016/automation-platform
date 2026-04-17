package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 점심카드 Modal Submit 페이로드 파싱 결과 VO.
 * 모달은 날짜(block_lunch_card_date)와 신청/취소 구분(block_lunch_card_action) 2개 필드로 구성된다.
 */
@Getter
public class LunchCardModalSubmit {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String userName;
  private final String date;
  private final String action;

  private LunchCardModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    String meta = payload.path("view").path("private_metadata").asText("");
    String rawName = payload.path("user").path("username").asText("");
    this.userName = meta.contains("|") ? meta.split("\\|", 2)[1] : rawName;

    JsonNode values = payload.path("view").path("state").path("values");

    this.date = values
        .path("block_lunch_card_date").path("action_lunch_card_date")
        .path("selected_date").asText("");

    this.action = values
        .path("block_lunch_card_action").path("action_lunch_card_action")
        .path("selected_option").path("value").asText("");
  }

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
  public static LunchCardModalSubmit parse(String urlEncodedBody) throws Exception {
    String decoded = URLDecoder.decode(
        urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
    return new LunchCardModalSubmit(OBJECT_MAPPER.readTree(decoded));
  }

  public boolean isViewSubmission() {
    return "view_submission".equals(type);
  }

  public boolean isValidAction() {
    return "apply".equals(action) || "cancel".equals(action);
  }

  public boolean hasDate() {
    return date != null && !date.isEmpty();
  }

  public boolean hasAction() {
    return action != null && !action.isEmpty();
  }

  public boolean isApply() {
    return "apply".equals(action);
  }

  public boolean isCancel() {
    return "cancel".equals(action);
  }
}
