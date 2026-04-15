package com.riman.automation.scheduler.service.collect;

import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.common.code.AbsenceTypeCode;
import com.riman.automation.common.code.ReportWeekCode;
import com.riman.automation.common.code.WorkStatusCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.report.DailyReportData.AbsenceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 일일 보고용 Google Calendar 부재/재택 이벤트 수집기.
 * GoogleCalendarClient로 이벤트를 조회하여 AbsenceItem 목록으로 변환한다.
 *
 * 이벤트 제목 형식:
 *   "재택(조주현)"           - 단독 재택
 *   "재택(조주현, 김철수)"   - 복수 재택, 쉼표 구분
 *   "연차(홍길동)"           - AbsenceTypeCode.ANNUAL_LEAVE
 *   "오전 반차(김철수)"      - AbsenceTypeCode.AM_HALF
 *   "병가(박영희)"           - AbsenceTypeCode.SICK_LEAVE
 *   "외근(이름)"             - WorkStatusCode 키워드 감지
 *
 * ticketCalendarId가 설정된 경우 티켓 캘린더도 함께 조회한다.
 * worker의 AbsenceService가 부재 이벤트를 티켓 캘린더(ticket_calendar_id)에도 저장하는 경우,
 * calendar_id만 조회하면 해당 이벤트가 누락되기 때문이다.
 * 이벤트 ID 기준으로 중복 제거하므로 두 캘린더가 동일해도 안전하다.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyAbsenceCollector {

  private final GoogleCalendarClient calendarClient;

  /**
   * 보고서 기간 내 부재/재택 이벤트 수집.
   *
   * @param calendarId 캘린더 ID (재택근무/부재 캘린더, config.json dailyReport.calendar_id)
   * @param ticketCalendarId 티켓 캘린더 ID (config.json dailyReport.ticket_calendar_id, null 허용)
   * @param baseDate 기준일 (KST 오늘)
   */
  public List<AbsenceItem> collect(String calendarId, String ticketCalendarId, LocalDate baseDate) {
    if (calendarId == null || calendarId.isBlank()) {
      log.warn("[DailyAbsenceCollector] calendar_id 미설정, 부재 수집 건너뜀");
      return List.of();
    }

    // 보고서 표시 기간: 금주(월~금), 금요일엔 차주 포함
    LocalDate startDate = ReportWeekCode.startDate(baseDate);
    LocalDate endDate = ReportWeekCode.endDate(baseDate);

    String timeMin = startDate + "T00:00:00+09:00";
    String timeMax = endDate + "T23:59:59+09:00";

    log.info("[DailyAbsenceCollector] 수집: {} ~ {}", startDate, endDate);

    // calendar_id + ticket_calendar_id 두 캘린더 병합 조회
    List<Event> mergedEvents = fetchAndMerge(calendarId, ticketCalendarId, timeMin, timeMax);

    List<AbsenceItem> result = new ArrayList<>();

    // 재택 이벤트 파싱
    for (Event event : mergedEvents) {
      if (titleOf(event).startsWith("재택(")) {
        parseRemoteEvent(event, baseDate, result);
      }
    }

    // AbsenceTypeCode 기반 부재 이벤트 파싱
    for (Event event : mergedEvents) {
      parseAbsenceTypeEvent(event, baseDate, result);
    }

    // 기타 부재 이벤트: WorkStatusCode 키워드 감지
    for (Event event : mergedEvents) {
      String title = titleOf(event);
      WorkStatusCode status = WorkStatusCode.detectFrom(title);
      log.debug("[DailyAbsenceCollector] 이벤트 제목='{}' → 감지상태={}", title, status);
      if (status.isNonOffice() && status != WorkStatusCode.REMOTE) {
        if (!isAbsenceTypeEvent(title)) {
          parseGenericAbsence(event, status, baseDate, result);
        }
      }
    }

    if (result.isEmpty()) {
      log.warn("[DailyAbsenceCollector] 수집 0건 — 전체 이벤트 제목 목록:");
      mergedEvents.forEach(e -> log.warn("  제목='{}'", titleOf(e)));
    }

    log.info("[DailyAbsenceCollector] 수집 완료: {}건", result.size());
    return result;
  }

  /**
   * calendarId + ticketCalendarId 두 캘린더를 조회하여 이벤트 ID 기준 중복 제거 후 반환.
   * ticketCalendarId가 null/blank이거나 calendarId와 동일하면 단일 조회.
   */
  private List<Event> fetchAndMerge(String calendarId, String ticketCalendarId,
                                    String timeMin, String timeMax) {
    List<Event> merged = new ArrayList<>(
        calendarClient.listEvents(calendarId, timeMin, timeMax, null));

    boolean hasTicketCal = ticketCalendarId != null
        && !ticketCalendarId.isBlank()
        && !ticketCalendarId.equals(calendarId);

    if (hasTicketCal) {
      log.info("[DailyAbsenceCollector] 티켓 캘린더 추가 조회: {}", ticketCalendarId);
      List<Event> ticketEvents =
          calendarClient.listEvents(ticketCalendarId, timeMin, timeMax, null);
      log.info("[DailyAbsenceCollector] 티켓 캘린더 이벤트: {}건", ticketEvents.size());

      Set<String> seenIds = new HashSet<>();
      merged.forEach(e -> seenIds.add(e.getId()));
      for (Event e : ticketEvents) {
        if (seenIds.add(e.getId())) merged.add(e);
      }
    }
    return merged;
  }

  private void parseRemoteEvent(Event event, LocalDate baseDate, List<AbsenceItem> result) {
    String title = titleOf(event);
    LocalDate date = dateOf(event);
    if (date == null) return;

    List<String> names = parseRemoteNames(title);
    if (names.isEmpty()) {
      result.add(buildItem(title, WorkStatusCode.REMOTE, date, baseDate));
    } else {
      names.forEach(name ->
          result.add(buildItem(name, WorkStatusCode.REMOTE, date, baseDate)));
    }
  }

  private void parseGenericAbsence(Event event, WorkStatusCode status,
                                   LocalDate baseDate, List<AbsenceItem> result) {
    LocalDate date = dateOf(event);
    if (date == null) return;
    result.add(buildItem(titleOf(event), status, date, baseDate));
  }

  private List<String> parseRemoteNames(String title) {
    List<String> names = new ArrayList<>();
    if (title == null || !title.startsWith("재택(") || !title.endsWith(")")) return names;
    String inner = title.substring("재택(".length(), title.length() - 1);
    for (String part : inner.split(",\\s*")) {
      String n = part.trim();
      if (!n.isEmpty()) names.add(n);
    }
    return names;
  }

  /**
   * AbsenceTypeCode 라벨로 시작하는 이벤트를 파싱하여 "이름(부재타입)" 형식으로 저장한다.
   */
  private void parseAbsenceTypeEvent(Event event, LocalDate baseDate, List<AbsenceItem> result) {
    String title = titleOf(event);
    if (title.startsWith("재택(")) return;

    AbsenceTypeCode absenceType = detectAbsenceTypeCode(title);
    if (absenceType == null) return;

    LocalDate date = dateOf(event);
    if (date == null) return;

    String label = absenceType.getLabel();
    String afterLabel = title.substring(label.length()).trim();

    if (!afterLabel.startsWith("(") || !afterLabel.endsWith(")")) {
      log.debug("[DailyAbsenceCollector] AbsenceTypeCode 형식 불일치, 건너뜀: title={}", title);
      return;
    }

    String name = afterLabel.substring(1, afterLabel.length() - 1).trim();
    if (name.isBlank()) {
      log.debug("[DailyAbsenceCollector] 이름 공란, 건너뜀: title={}", title);
      return;
    }

    String displayName = name + "(" + label + ")";
    result.add(AbsenceItem.builder()
        .memberName(displayName)
        .workStatus(WorkStatusCode.UNKNOWN)
        .date(date)
        .today(baseDate.equals(date))
        .build());

    log.debug("[DailyAbsenceCollector] AbsenceTypeCode 파싱: title='{}' → '{}'",
        title, displayName);
  }

  private boolean isAbsenceTypeEvent(String title) {
    return detectAbsenceTypeCode(title) != null;
  }

  private AbsenceTypeCode detectAbsenceTypeCode(String title) {
    if (title == null || title.isBlank()) return null;
    for (AbsenceTypeCode code : AbsenceTypeCode.values()) {
      if (title.startsWith(code.getLabel())) return code;
    }
    return null;
  }

  private LocalDate dateOf(Event event) {
    if (event.getStart() == null) return null;
    com.google.api.client.util.DateTime date = event.getStart().getDate();
    if (date != null) {
      return DateTimeUtil.parseDate(date.toStringRfc3339().substring(0, 10));
    }
    com.google.api.client.util.DateTime dateTime = event.getStart().getDateTime();
    if (dateTime != null) {
      String str = dateTime.toStringRfc3339();
      return DateTimeUtil.parseDate(str.length() >= 10 ? str.substring(0, 10) : str);
    }
    return null;
  }

  private String titleOf(Event event) {
    return event.getSummary() != null ? event.getSummary() : "";
  }

  private AbsenceItem buildItem(String name, WorkStatusCode status,
                                LocalDate date, LocalDate baseDate) {
    return AbsenceItem.builder()
        .memberName(name)
        .workStatus(status)
        .date(date)
        .today(baseDate.equals(date))
        .build();
  }
}
