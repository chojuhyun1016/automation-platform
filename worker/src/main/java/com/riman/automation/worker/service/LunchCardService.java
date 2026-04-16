package com.riman.automation.worker.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;

/**
 * 점심카드 Google Calendar 처리 서비스.
 * 날짜별 1인 1이벤트 모델로, 동일 날짜에 2명이 신청하면 별개의 이벤트 2개를 생성한다.
 * 취소 시 이벤트가 없으면 DLQ 방지를 위해 조용히 종료한다.
 */
@Slf4j
public class LunchCardService {

  private static final String SEARCH_QUERY = "점심카드";
  private static final String SUMMARY_PREFIX = "점심카드";

  private final CalendarService calendarService;

  public LunchCardService(CalendarService calendarService) {
    this.calendarService = calendarService;
  }

  /**
   * 특정 날짜의 모든 점심카드 이벤트를 조회한다.
   *
   * @param calendarId 대상 캘린더 ID
   * @param date       조회 날짜 (yyyy-MM-dd)
   * @return 점심카드 이벤트 목록
   */
  public List<Event> findLunchCardEvents(String calendarId, String date) {
    String timeMin = date + "T00:00:00+09:00";
    String timeMax = date + "T23:59:59+09:00";
    return calendarService.listCalendarEvents(calendarId, timeMin, timeMax, SEARCH_QUERY);
  }

  /**
   * 특정 날짜에서 이름이 일치하는 점심카드 이벤트를 찾는다.
   *
   * @param calendarId 대상 캘린더 ID
   * @param name       사용자 이름
   * @param date       조회 날짜 (yyyy-MM-dd)
   * @return 일치하는 이벤트 또는 null
   */
  public Event findLunchCardEvent(String calendarId, String name, String date) {
    String expected = buildSummary(name);
    List<Event> events = findLunchCardEvents(calendarId, date);
    return events.stream()
        .filter(e -> expected.equals(e.getSummary()))
        .findFirst()
        .orElse(null);
  }

  /**
   * 점심카드를 신청한다. 동일 이벤트가 이미 있으면 멱등 처리한다.
   *
   * @param calendarId 대상 캘린더 ID
   * @param name       사용자 이름
   * @param date       신청 날짜 (yyyy-MM-dd)
   */
  public void applyLunchCard(String calendarId, String name, String date) {
    String summary = buildSummary(name);

    Event existing = findLunchCardEvent(calendarId, name, date);
    if (existing != null) {
      log.info("이미 존재하는 점심카드 이벤트 → 무시: summary={}, date={}", summary, date);
      return;
    }

    LocalDate localDate = LocalDate.parse(date);
    Event event = new Event()
        .setSummary(summary)
        .setStart(new EventDateTime().setDate(new DateTime(localDate.toString())))
        .setEnd(new EventDateTime().setDate(new DateTime(localDate.plusDays(1).toString())))
        .setTransparency("transparent");

    calendarService.insertCalendarEvent(calendarId, event);
    log.info("점심카드 이벤트 생성: summary={}, date={}", summary, date);
  }

  /**
   * 점심카드를 취소한다. 이벤트가 없으면 DLQ 방지를 위해 조용히 종료한다.
   *
   * @param calendarId 대상 캘린더 ID
   * @param name       사용자 이름
   * @param date       취소 날짜 (yyyy-MM-dd)
   */
  public void cancelLunchCard(String calendarId, String name, String date) {
    Event existing = findLunchCardEvent(calendarId, name, date);

    if (existing == null) {
      log.info("취소할 점심카드 이벤트 없음 → 무시: name={}, date={}", name, date);
      return;
    }

    calendarService.deleteCalendarEvent(calendarId, existing.getId());
    log.info("점심카드 이벤트 삭제: summary={}, date={}, eventId={}",
        existing.getSummary(), date, existing.getId());
  }

  /**
   * 점심카드 이벤트 제목을 "점심카드(홍길동)" 형식으로 구성한다.
   */
  private String buildSummary(String name) {
    return SUMMARY_PREFIX + "(" + name + ")";
  }
}
