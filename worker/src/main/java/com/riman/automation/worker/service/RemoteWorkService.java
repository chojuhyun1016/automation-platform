package com.riman.automation.worker.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 재택근무 신청/취소 처리 서비스.
 * 날짜별 "재택(이름1, 이름2...)" 단일 이벤트로 여러 신청자를 누적 관리한다.
 * 신청 시 동일 이름이 있으면 멱등 무시, 취소 시 이벤트가 없거나 이름이 포함되지 않으면 DLQ 방지를 위해 조용히 종료한다.
 * 마지막 1명을 취소하면 이벤트 자체를 삭제한다.
 */
@Slf4j
public class RemoteWorkService {

  private static final String TITLE_PREFIX = "재택(";
  private static final String TITLE_SUFFIX = ")";

  private final CalendarService calendarService;
  private final ConfigService configService;

  public RemoteWorkService(CalendarService calendarService, ConfigService configService) {
    this.calendarService = calendarService;
    this.configService = configService;
  }

  /**
   * 재택 신청(apply) 또는 취소(cancel)를 처리한다. RemoteWorkFacade가 호출한다.
   *
   * @param action "apply" 또는 "cancel"
   * @param name   한글 이름
   * @param date   날짜 "yyyy-MM-dd"
   */
  public void process(String action, String name, String date) {
    log.info("재택 처리: action={}, name={}, date={}", action, name, date);

    String calendarId = resolveCalendarId();

    if ("apply".equals(action)) {
      handleApply(calendarId, name, date);
    } else if ("cancel".equals(action)) {
      handleCancel(calendarId, name, date);
    } else {
      log.warn("알 수 없는 action 무시: action={}", action);
    }
  }

  /**
   * 재택 신청 처리.
   * 기존 이벤트가 없으면 신규 생성하고, 있으면 이름을 제목에 누적한다. 이미 포함된 이름은 멱등 무시한다.
   */
  private void handleApply(String calendarId, String name, String date) {
    try {
      CalendarService.RemoteWorkCalendarInfo existing =
          calendarService.findRemoteWorkEvent(calendarId, date);

      if (existing == null) {
        createEvent(calendarId, name, date);
        log.info("재택 이벤트 생성 완료: name={}, date={}", name, date);
      } else {
        if (isNameInTitle(existing.summary, name)) {
          log.info("이미 등록된 이름, 중복 무시: name={}, date={}, title={}",
              name, date, existing.summary);
          return;
        }
        String newTitle = appendName(existing.summary, name);
        calendarService.updateRemoteWorkEventTitle(calendarId, existing.eventId, newTitle);
        log.info("재택 이름 추가: {} → {}", existing.summary, newTitle);
      }
    } catch (Exception e) {
      log.error("재택 신청 처리 실패: name={}, date={}", name, date, e);
      throw new RuntimeException("재택 신청 캘린더 처리 실패", e);
    }
  }

  /**
   * 재택 취소 처리.
   * 이벤트나 이름이 없으면 조용히 종료한다. 이름 제거 후 아무도 남지 않으면 이벤트 자체를 삭제한다.
   */
  private void handleCancel(String calendarId, String name, String date) {
    try {
      CalendarService.RemoteWorkCalendarInfo existing =
          calendarService.findRemoteWorkEvent(calendarId, date);

      if (existing == null) {
        log.info("취소할 재택 이벤트 없음 (무시): name={}, date={}", name, date);
        return;
      }

      if (!isNameInTitle(existing.summary, name)) {
        log.info("취소할 이름이 이벤트에 없음 (무시): name={}, title={}", name, existing.summary);
        return;
      }

      String newTitle = removeName(existing.summary, name);

      if (isEventEmpty(newTitle)) {
        calendarService.deleteRemoteWorkEvent(calendarId, existing.eventId);
        log.info("재택 이벤트 삭제 완료 (마지막 취소): name={}, date={}", name, date);
      } else {
        calendarService.updateRemoteWorkEventTitle(calendarId, existing.eventId, newTitle);
        log.info("재택 이름 제거: {} → {}", existing.summary, newTitle);
      }
    } catch (Exception e) {
      log.error("재택 취소 처리 실패: name={}, date={}", name, date, e);
      throw new RuntimeException("재택 취소 캘린더 처리 실패", e);
    }
  }

  /**
   * 종일 이벤트로 재택 이벤트를 신규 생성한다. transparency는 "transparent".
   */
  private void createEvent(String calendarId, String name, String date) throws Exception {
    LocalDate d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);

    Event event = new Event()
        .setSummary(TITLE_PREFIX + name + TITLE_SUFFIX)
        .setStart(new EventDateTime().setDate(new DateTime(date)))
        .setEnd(new EventDateTime().setDate(new DateTime(d.plusDays(1).toString())))
        .setTransparency("transparent");

    calendarService.insertRemoteWorkEvent(calendarId, event);
  }

  /**
   * "재택(조주현)" → "재택(조주현, 김철수)". 기존 제목 뒤에 이름을 누적한다.
   */
  private String appendName(String title, String name) {
    return title.substring(0, title.length() - 1) + ", " + name + TITLE_SUFFIX;
  }

  /**
   * "재택(조주현, 김철수, 박영희)"에서 특정 이름 하나를 제거한다.
   */
  private String removeName(String title, String name) {
    String inner = title.substring(TITLE_PREFIX.length(), title.length() - 1);
    StringBuilder sb = new StringBuilder();

    for (String part : inner.split(",\\s*")) {
      String trimmed = part.trim();
      if (!trimmed.equals(name)) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(trimmed);
      }
    }
    return TITLE_PREFIX + sb + TITLE_SUFFIX;
  }

  /**
   * 이름이 제목의 이름 목록에 포함되는지 확인한다 (완전 일치).
   */
  private boolean isNameInTitle(String title, String name) {
    String inner = title.substring(TITLE_PREFIX.length(), title.length() - 1);
    for (String part : inner.split(",\\s*")) {
      if (part.trim().equals(name)) return true;
    }
    return false;
  }

  /**
   * 제목의 이름 목록이 비어 있는지 확인한다 ("재택()" 상태).
   */
  private boolean isEventEmpty(String title) {
    return title.substring(TITLE_PREFIX.length(), title.length() - 1).trim().isEmpty();
  }

  /**
   * 재택 캘린더 ID를 결정한다.
   * config.json의 remoteWork.calendar_id를 우선 사용하고, 미설정이면 "primary"로 폴백한다.
   */
  private String resolveCalendarId() {
    try {
      String calendarId = configService.getRemoteWorkCalendarId();
      if (calendarId != null && !calendarId.isEmpty()) {
        return calendarId;
      }
      log.warn("remoteWork calendar_id 미설정, primary 사용");
    } catch (Exception e) {
      log.error("remoteWork calendarId 조회 실패, primary 사용", e);
    }
    return "primary";
  }
}
