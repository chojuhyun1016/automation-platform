package com.riman.automation.worker.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.riman.automation.worker.dto.sqs.ScheduleMessage;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.ScheduleEventMappingService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 일정등록/삭제 처리 Facade.
 * 등록 순서: 파싱 → 중복 체크 → 캘린더 ID 조회 → 이벤트 생성 → DynamoDB 매핑 저장 → 중복 방지 저장.
 * 삭제 순서: 파싱 → DynamoDB 매핑 조회(소유권 확인) → 캘린더 이벤트 삭제 → 매핑 삭제.
 * 캘린더 처리 실패는 DLQ 방지를 위해 예외를 삼킨다. AbsenceFacade, RemoteWorkFacade와 동일한 설계 패턴을 따른다.
 */
@Slf4j
public class ScheduleFacade {

  private static final String DEDUPE_PREFIX = "SCHEDULE#";

  private final ObjectMapper objectMapper;
  private final ConfigService configService;
  private final CalendarService calendarService;
  private final DedupeService dedupeService;
  private final ScheduleEventMappingService mappingService;

  /**
   * WorkerHandler가 사용하는 생성자. 공유 서비스 인스턴스를 주입받아 중복 생성을 방지한다.
   */
  public ScheduleFacade(
      ConfigService configService,
      CalendarService calendarService,
      DedupeService dedupeService,
      ScheduleEventMappingService mappingService) {
    this.objectMapper = new ObjectMapper();
    this.configService = configService;
    this.calendarService = calendarService;
    this.dedupeService = dedupeService;
    this.mappingService = mappingService;
  }

  public void handle(String messageBody) {

    ScheduleMessage msg;
    try {
      msg = objectMapper.readValue(messageBody, ScheduleMessage.class);
    } catch (Exception e) {
      log.error("ScheduleMessage 파싱 실패: body={}", messageBody, e);
      throw new RuntimeException("ScheduleMessage 파싱 실패", e);
    }

    log.info("일정 처리 시작: eventId={}, user={}, action={}, title={}, start={}",
        msg.getEventId(), msg.getUserName(), msg.getAction(),
        msg.getTitle(), msg.getStartDateTime());

    if (msg.isRegister()) {
      handleRegister(msg);
    } else if (msg.isDelete()) {
      handleDelete(msg);
    } else {
      log.warn("알 수 없는 action: {}, eventId={}", msg.getAction(), msg.getEventId());
    }
  }

  /**
   * 일정 등록 처리.
   * 유효한 캘린더 ID가 없으면 스킵하고, 이벤트 생성 실패는 DLQ 방지를 위해 예외를 삼킨다.
   */
  private void handleRegister(ScheduleMessage msg) {

    String dedupeKey = DEDUPE_PREFIX + msg.getEventId();
    if (dedupeService.isDuplicateByKey(dedupeKey)) {
      log.warn("일정 중복 이벤트 감지, 스킵: eventId={}", msg.getEventId());
      return;
    }

    String calendarId = resolveCalendarId();
    if ("primary".equals(calendarId)) {
      log.warn("유효한 캘린더 ID 없음, 일정 등록 스킵: eventId={}", msg.getEventId());
      return;
    }

    String fullTitle = "[일정] " + msg.getTitle();
    try {
      Event event = buildScheduleEvent(msg, fullTitle);
      Event created = calendarService.insertCalendarEvent(calendarId, event);

      log.info("일정 캘린더 이벤트 생성: eventId={}, calendarEventId={}, title={}",
          msg.getEventId(), created.getId(), fullTitle);

      mappingService.saveMapping(
          msg.getSlackUserId(),
          msg.getUserName() != null ? msg.getUserName() : "",
          msg.getKoreanName() != null ? msg.getKoreanName() : "",
          created.getId(),
          calendarId,
          fullTitle,
          msg.getStartDateTime(),
          msg.getEndDateTime()
      );

    } catch (Exception e) {
      log.error("일정 캘린더 생성 실패 (DLQ 방지): eventId={}, title={}",
          msg.getEventId(), fullTitle, e);
    }

    dedupeService.saveEventKey(dedupeKey);
  }

  /**
   * 일정 삭제 처리.
   * DynamoDB 매핑으로 소유권을 확인한 뒤에만 캘린더 이벤트를 삭제한다.
   */
  private void handleDelete(ScheduleMessage msg) {

    String calendarEventId = msg.getCalendarEventId();
    if (calendarEventId == null || calendarEventId.isBlank()) {
      log.warn("삭제 대상 calendarEventId 없음: slackUserId={}", msg.getSlackUserId());
      return;
    }

    // 소유권 확인: 본인이 등록한 일정인지 DynamoDB에서 재확인
    ScheduleEventMappingService.MappingEntry entry =
        mappingService.findMapping(msg.getSlackUserId(), calendarEventId);
    if (entry == null) {
      log.warn("삭제 대상 매핑 없음 (이미 삭제됐거나 다른 사람 일정): slackUserId={}, eventId={}",
          msg.getSlackUserId(), calendarEventId);
      return;
    }

    try {
      calendarService.deleteCalendarEvent(entry.calendarId, calendarEventId);
      log.info("일정 캘린더 이벤트 삭제: slackUserId={}, eventId={}, title={}",
          msg.getSlackUserId(), calendarEventId, entry.title);
    } catch (Exception e) {
      log.error("일정 캘린더 삭제 실패 (DLQ 방지): eventId={}", calendarEventId, e);
      return;
    }

    mappingService.deleteMapping(msg.getSlackUserId(), calendarEventId);
  }

  /**
   * 일정 캘린더 ID를 결정한다.
   * 폴백 체인: absence.calendar_id → remoteWork.calendar_id → CCE routing → "primary".
   */
  private String resolveCalendarId() {
    try {
      return configService.getScheduleCalendarId();
    } catch (Exception e) {
      log.warn("캘린더 ID 조회 실패, primary 폴백: {}", e.getMessage());
      return "primary";
    }
  }

  /**
   * ScheduleMessage를 Google Calendar Event 객체로 변환한다.
   * startDateTime 문자열에 "T"가 포함되면 날짜+시간 이벤트, 아니면 종일 이벤트로 생성한다.
   * 종료 시간 미입력 시 시작 시간 +1시간으로 자동 설정한다. 알림은 최대 3개까지 popup 오버라이드로 등록된다.
   */
  private Event buildScheduleEvent(ScheduleMessage msg, String fullTitle) {
    Event event = new Event();
    event.setSummary(fullTitle);

    String description = buildDescription(msg);
    if (!description.isBlank()) {
      event.setDescription(description);
    }

    boolean isDateTime = msg.getStartDateTime() != null
        && msg.getStartDateTime().contains("T");

    EventDateTime start;
    EventDateTime end;

    if (isDateTime) {
      String startRfc = toRfc3339Kst(msg.getStartDateTime());
      String endRfc;
      if (msg.getEndDateTime() != null && msg.getEndDateTime().contains("T")) {
        endRfc = toRfc3339Kst(msg.getEndDateTime());
      } else {
        endRfc = addOneHour(msg.getStartDateTime());
      }
      start = new EventDateTime().setDateTime(new DateTime(startRfc));
      end = new EventDateTime().setDateTime(new DateTime(endRfc));
    } else {
      String startDate = msg.getStartDateTime();
      String endDate = msg.getEndDateTime() != null && !msg.getEndDateTime().isBlank()
          ? msg.getEndDateTime()
          : startDate;
      start = new EventDateTime().setDate(new DateTime(startDate));
      end = new EventDateTime().setDate(new DateTime(endDate));
    }

    event.setStart(start);
    event.setEnd(end);

    List<Integer> reminderMinutes = msg.getSafeReminderMinutes();
    if (!reminderMinutes.isEmpty()) {
      List<EventReminder> overrides = new ArrayList<>();
      for (Integer minutes : reminderMinutes) {
        overrides.add(new EventReminder()
            .setMethod("popup")
            .setMinutes(minutes));
      }
      Event.Reminders reminders = new Event.Reminders();
      reminders.setUseDefault(false);
      reminders.setOverrides(overrides);
      event.setReminders(reminders);
    }

    return event;
  }

  /**
   * 이벤트 description(등록자, 시간, 알림, 내용, URL)을 구성한다.
   */
  private String buildDescription(ScheduleMessage msg) {
    StringBuilder sb = new StringBuilder();

    String authorDisplay = (msg.getKoreanName() != null && !msg.getKoreanName().isBlank())
        ? msg.getKoreanName() + " (@" + (msg.getUserName() != null ? msg.getUserName() : "") + ")"
        : (msg.getUserName() != null ? msg.getUserName() : "");
    if (!authorDisplay.isBlank()) {
      sb.append("📝 등록자: ").append(authorDisplay).append("\n");
    }

    boolean isDateTime = msg.getStartDateTime() != null && msg.getStartDateTime().contains("T");
    if (isDateTime) {
      String startStr = formatKstDisplay(msg.getStartDateTime());
      String endDateTime = (msg.getEndDateTime() != null && msg.getEndDateTime().contains("T"))
          ? msg.getEndDateTime() : addOneHourRaw(msg.getStartDateTime());
      String endStr = formatKstDisplay(endDateTime);
      sb.append("🕐 시간: ").append(startStr).append(" ~ ").append(endStr).append("\n");
    } else {
      sb.append("📅 일정: 종일\n");
    }

    List<Integer> reminders = msg.getSafeReminderMinutes();
    if (!reminders.isEmpty()) {
      sb.append("🔔 알림: ");
      java.util.List<String> labels = new java.util.ArrayList<>();
      for (int m : reminders) {
        if (m == 60) labels.add("1시간 전");
        else if (m == 120) labels.add("2시간 전");
        else if (m == 180) labels.add("3시간 전");
        else if (m == 1440) labels.add("1일 전");
        else if (m == 2880) labels.add("2일 전");
        else if (m == 4320) labels.add("3일 전");
        else labels.add(m + "분 전");
      }
      sb.append(String.join(", ", labels)).append("\n");
    }

    if (msg.getDescription() != null && !msg.getDescription().isBlank()) {
      sb.append("\n").append(msg.getDescription());
    }

    if (msg.getUrl() != null && !msg.getUrl().isBlank()) {
      sb.append("\n\n🔗 ").append(msg.getUrl());
    }

    return sb.toString().trim();
  }

  /**
   * "yyyy-MM-ddTHH:mm" → "yyyy-MM-dd HH:mm" (KST 사람이 읽기 쉬운 표시용).
   */
  private String formatKstDisplay(String dateTime) {
    if (dateTime == null || dateTime.length() < 16) return dateTime;
    return dateTime.substring(0, 10) + " " + dateTime.substring(11, 16);
  }

  /**
   * "yyyy-MM-ddTHH:mm"를 1시간 더한 raw 문자열로 반환한다 (오프셋 없음).
   */
  private String addOneHourRaw(String dateTime) {
    try {
      java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
          dateTime.substring(0, 16),
          java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
      java.time.LocalDateTime plus1h = ldt.plusHours(1);
      return plus1h.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    } catch (Exception e) {
      return dateTime;
    }
  }

  /**
   * 종료 시간 미입력 시 사용할 "시작 +1시간"의 RFC3339 KST 문자열을 반환한다.
   */
  private String addOneHour(String dateTime) {
    String raw = addOneHourRaw(dateTime);
    return toRfc3339Kst(raw);
  }

  /**
   * "yyyy-MM-ddTHH:mm" → "yyyy-MM-ddTHH:mm:00+09:00" (KST RFC3339).
   * Lambda는 UTC 환경이므로 KST 오프셋을 명시적으로 붙인다.
   */
  private String toRfc3339Kst(String dateTime) {
    if (dateTime == null) return "";
    if (dateTime.length() == 16) {
      return dateTime + ":00+09:00";
    }
    return dateTime;
  }
}
