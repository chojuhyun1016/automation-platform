package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 일정등록/삭제 SQS 메시지 DTO
 *
 * <p>WorkerMessageService.sendSchedule() 에서 직렬화하여 SQS 전송.
 * WorkerHandler → ScheduleFacade 에서 역직렬화.
 *
 * <p><b>필드 설명:</b>
 * <pre>
 *   messageType  : "schedule" 고정
 *   eventId      : UUID (중복 방지 키)
 *   receivedAt   : ISO-8601 타임스탬프
 *   action       : "register" | "delete"
 *   slackUserId  : 요청자 Slack User ID (삭제 시 소유권 확인용)
 *   name         : 한글 이름 (만든 사람, 모달 입력값)
 *   title        : 일정 제목 ([일정] prefix는 Worker에서 붙임)
 *   description  : 내용 (optional, null 허용)
 *   startDateTime: 시작 일시 (yyyy-MM-dd'T'HH:mm 또는 yyyy-MM-dd)
 *   endDateTime  : 종료 일시 (optional, null이면 startDateTime 당일)
 *   reminderMinutes: 알림 분 목록 (최대 3개, empty 허용)
 *   url          : 관련 URL (optional, null 허용)
 *   calendarEventId: 삭제 시 Google Calendar Event ID (DynamoDB 조회)
 * </pre>
 *
 * <p>AbsenceMessage, RemoteWorkMessage 와 동일한 VO 패턴을 따른다.
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

    /**
     * "register" | "delete"
     */
    @JsonProperty("action")
    private String action;

    @JsonProperty("slackUserId")
    private String slackUserId;  // WorkerMessageService.sendScheduleRegister 키와 일치

    /**
     * Slack user_name (WorkerMessageService의 "userName" 키)
     */
    @JsonProperty("userName")
    private String userName;

    /**
     * 한글 이름 (WorkerMessageService의 "koreanName" 키)
     */
    @JsonProperty("koreanName")
    private String koreanName;

    /**
     * 일정 제목 ([일정] prefix 없음, Worker에서 붙임)
     */
    @JsonProperty("title")
    private String title;

    /**
     * 내용 (optional)
     */
    @JsonProperty("description")
    private String description;

    /**
     * 시작 일시 — "yyyy-MM-dd'T'HH:mm" 또는 "yyyy-MM-dd"
     */
    @JsonProperty("startDateTime")
    private String startDateTime;

    /**
     * 종료 일시 (optional, null이면 startDateTime 당일로 처리)
     */
    @JsonProperty("endDateTime")
    private String endDateTime;

    /**
     * 알림 분 목록 (예: [60, 180, 1440])
     */
    @JsonProperty("reminderMinutes")
    private List<Integer> reminderMinutes;

    /**
     * 관련 URL (optional)
     */
    @JsonProperty("url")
    private String url;

    /**
     * 삭제 시 사용 — DynamoDB에서 조회한 Google Calendar Event ID
     */
    @JsonProperty("calendarEventId")
    private String calendarEventId;

    // =========================================================================
    // 헬퍼
    // =========================================================================

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
