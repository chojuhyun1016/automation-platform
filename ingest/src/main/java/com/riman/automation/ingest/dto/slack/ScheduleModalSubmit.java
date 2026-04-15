package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Slack 일정등록 Modal Submit 페이로드 파싱 VO.
 * 제목, 내용, 날짜/시간, 알림 3개 드롭다운, URL 등 9개 필드로 구성된다.
 * private_metadata 형식은 "userId|slackDisplayName|한글이름" 이다.
 * 알림 드롭다운의 value "0" 은 '없음' 을 의미하며 reminderMinutes 에서 제외된다.
 */
@Getter
public class ScheduleModalSubmit {

  public static final String CALLBACK_ID = "schedule_submit";

  private static final ObjectMapper OM = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String slackUserName;
  private final String koreanName;
  private final String title;
  private final String description;
  private final String startDate;
  private final String startTime;
  private final String endTime;
  private final List<Integer> reminderMinutes;
  private final String url;

  private ScheduleModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    // private_metadata 형식: "userId|slackDisplayName|한글이름".
    String meta = payload.path("view").path("private_metadata").asText("");
    String[] parts = meta.split("\\|", 3);
    this.slackUserName = parts.length > 1 ? parts[1] : payload.path("user").path("username").asText("");
    this.koreanName = parts.length > 2 ? parts[2] : this.slackUserName;

    JsonNode values = payload.path("view").path("state").path("values");

    this.title = values
        .path("block_schedule_title")
        .path("action_schedule_title")
        .path("value").asText("").trim();

    this.description = values
        .path("block_schedule_description")
        .path("action_schedule_description")
        .path("value").asText("").trim();

    this.startDate = values
        .path("block_schedule_start_date")
        .path("action_schedule_start_date")
        .path("selected_date").asText("").trim();

    this.startTime = values
        .path("block_schedule_start_time")
        .path("action_schedule_start_time")
        .path("selected_time").asText("").trim();

    this.endTime = values
        .path("block_schedule_end_time")
        .path("action_schedule_end_time")
        .path("selected_time").asText("").trim();

    this.reminderMinutes = parseReminderDropdowns(values);

    this.url = values
        .path("block_schedule_url")
        .path("action_schedule_url")
        .path("value").asText("").trim();
  }

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
  public static ScheduleModalSubmit parse(String urlEncodedBody) throws Exception {
    String decoded = URLDecoder.decode(
        urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
    return new ScheduleModalSubmit(OM.readTree(decoded));
  }

  public boolean isViewSubmission() {
    return "view_submission".equals(type);
  }

  public boolean hasTitle() {
    return title != null && !title.isBlank();
  }

  public boolean hasStartDate() {
    return startDate != null && !startDate.isBlank();
  }

  /**
   * 시작 일시 문자열을 반환한다.
   * 시간이 있으면 "yyyy-MM-dd'T'HH:mm", 없으면 "yyyy-MM-dd" 형식이다.
   */
  public String getStartDateTime() {
    return (startTime != null && !startTime.isBlank())
        ? startDate + "T" + startTime
        : startDate;
  }

  /**
   * 종료 일시 문자열을 반환한다.
   * 종료시간이 없으면 null 을 반환하며 상위 로직에서 +1시간으로 자동 보정한다.
   */
  public String getEndDateTime() {
    if (endTime != null && !endTime.isBlank()) {
      return startDate + "T" + endTime;
    }
    return null;
  }

  /**
   * 종료 시간 입력 여부 (Slack 모달 hint 표시 판단용).
   */
  public boolean hasEndTime() {
    return endTime != null && !endTime.isBlank();
  }

  /**
   * 알림 드롭다운 3개를 파싱하여 분 단위 리스트로 변환한다.
   * value "0" 은 '없음' 이므로 제외하고, 중복 값도 제거한다.
   */
  private List<Integer> parseReminderDropdowns(JsonNode values) {
    List<Integer> result = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      String val = values
          .path("block_schedule_reminder_" + i)
          .path("action_schedule_reminder_" + i)
          .path("selected_option")
          .path("value").asText("").trim();
      if (val.isBlank() || "0".equals(val)) continue;
      try {
        int minutes = Integer.parseInt(val);
        if (!result.contains(minutes)) {
          result.add(minutes);
        }
      } catch (NumberFormatException ignored) {
      }
    }
    return result;
  }
}
