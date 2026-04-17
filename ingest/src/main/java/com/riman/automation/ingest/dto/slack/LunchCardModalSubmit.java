package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 점심카드 Modal Submit 페이로드 파싱 결과 VO.
 * 모달은 날짜(block_lunch_card_date) 1개 필드로 구성되며, action(apply/cancel)은 private_metadata 3번째 필드에서 결정된다.
 * private_metadata 형식: {@code userId|userName|action}
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
    String[] metaParts = meta.split("\\|");
    this.userName = metaParts.length >= 2 ? metaParts[1] : rawName;
    this.action = metaParts.length >= 3 ? metaParts[2] : "";

    JsonNode values = payload.path("view").path("state").path("values");

    this.date = values
        .path("block_lunch_card_date").path("action_lunch_card_date")
        .path("selected_date").asText("");
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
