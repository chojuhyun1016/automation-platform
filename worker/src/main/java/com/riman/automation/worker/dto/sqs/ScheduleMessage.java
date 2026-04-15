package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 일정등록/삭제 SQS 메시지 DTO.
 * ingest의 WorkerMessageService.sendSchedule()이 직렬화하여 발행하고, ScheduleFacade가 역직렬화한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduleMessage {

  @JsonProperty("messageType")
  private String messageType;

  @JsonProperty("eventId")
  private String eventId;

  @JsonProperty("receivedAt")
  private String receivedAt;

  /** "register" 또는 "delete". */
  @JsonProperty("action")
  private String action;

  /** 요청자 Slack User ID. 삭제 시 소유권 확인에 사용한다. */
  @JsonProperty("slackUserId")
  private String slackUserId;

  /** Slack user_name. */
  @JsonProperty("userName")
  private String userName;

  /** 한글 이름. */
  @JsonProperty("koreanName")
  private String koreanName;

  /** 일정 제목. "[일정]" prefix는 Worker에서 붙인다. */
  @JsonProperty("title")
  private String title;

  /** 내용 (선택값). */
  @JsonProperty("description")
  private String description;

  /** 시작 일시. "yyyy-MM-dd'T'HH:mm" 또는 "yyyy-MM-dd" 형식. */
  @JsonProperty("startDateTime")
  private String startDateTime;

  /** 종료 일시 (선택값). null이면 startDateTime 당일로 처리한다. */
  @JsonProperty("endDateTime")
  private String endDateTime;

  /** 알림 분 목록 (예: [60, 180, 1440]). */
  @JsonProperty("reminderMinutes")
  private List<Integer> reminderMinutes;

  /** 관련 URL (선택값). */
  @JsonProperty("url")
  private String url;

  /** 삭제 시 사용한다. DynamoDB에서 조회한 Google Calendar Event ID. */
  @JsonProperty("calendarEventId")
  private String calendarEventId;

  public boolean isRegister() {
    return "register".equals(action);
  }

  public boolean isDelete() {
    return "delete".equals(action);
  }

  public List<Integer> getSafeReminderMinutes() {
    return reminderMinutes != null ? reminderMinutes : Collections.emptyList();
  }
}
