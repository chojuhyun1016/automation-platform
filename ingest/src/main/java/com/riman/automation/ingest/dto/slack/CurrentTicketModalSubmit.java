package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * /현재티켓 Modal Submit 페이로드 파싱 결과 VO.
 * callback_id 는 {@code current_ticket_submit} 이며 조회 기간(daily/weekly/monthly/quarterly)을 담는다.
 */
@Getter
public class CurrentTicketModalSubmit {

  public static final String CALLBACK_ID = "current_ticket_submit";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String period;

  private CurrentTicketModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    this.period = payload
        .path("view").path("state").path("values")
        .path("block_ticket_period").path("action_ticket_period")
        .path("selected_option").path("value").asText("quarterly");
  }

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
  public static CurrentTicketModalSubmit parse(String urlEncodedBody) throws Exception {
    String decoded = URLDecoder.decode(
        urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
    return new CurrentTicketModalSubmit(OBJECT_MAPPER.readTree(decoded));
  }

  public boolean isViewSubmission() {
    return "view_submission".equals(type);
  }

  public boolean isDaily() {
    return "daily".equals(period);
  }

  public boolean isWeekly() {
    return "weekly".equals(period);
  }

  public boolean isMonthly() {
    return "monthly".equals(period);
  }

  public boolean isQuarterly() {
    return "quarterly".equals(period);
  }
}
