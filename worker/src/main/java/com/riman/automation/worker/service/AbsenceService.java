package com.riman.automation.worker.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.worker.dto.sqs.AbsenceMessage;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 부재 Google Calendar 처리 서비스
 *
 * <p><b>1인 1이벤트 방식</b> — 재택근무(누적)와 달리 동일 날짜에 2명이 신청하면 이벤트 2개 생성.
 * 이벤트 제목: "연차(홍길동)", "오전 반차(김철수)" 등 이름 포함 단독 이벤트.
 *
 * <p><b>취소(cancel):</b>
 * 해당 날짜·유형·이름이 일치하는 이벤트를 찾아 삭제.
 * 없으면 조용히 종료 (DLQ 방지).
 *
 * <p><b>연차 기간:</b>
 * startDate ~ endDate 각 날짜별로 별도 이벤트 생성/삭제.
 *
 * <p>Google Calendar API는 CalendarService의 공개 메서드를 통해 호출한다.
 */
@Slf4j
public class AbsenceService {

    private final CalendarService calendarService;

    public AbsenceService(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * 부재 등록/취소 처리
     *
     * @param calendarId 캘린더 ID
     * @param msg        파싱된 AbsenceMessage (name에 한글 이름, endDate 보정 완료 상태)
     */
    public void process(String calendarId, AbsenceMessage msg) {
        LocalDate start = LocalDate.parse(msg.getStartDate(), DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate end = LocalDate.parse(msg.getEffectiveEndDate(), DateTimeFormatter.ISO_LOCAL_DATE);

        if (end.isBefore(start)) {
            log.warn("종료일 < 시작일 → 시작일로 보정: start={}, end={}", start, end);
            end = start;
        }

        log.info("부재 {} 처리: name={}, type={}, {} ~ {}",
                msg.getAction(), msg.getName(), msg.getAbsenceType(), start, end);

        LocalDate current = start;
        while (!current.isAfter(end)) {
            if (msg.isApply()) {
                applyDate(calendarId, msg, current);
            } else {
                cancelDate(calendarId, msg, current);
            }
            current = current.plusDays(1);
        }

        log.info("부재 {} 완료: name={}, type={}", msg.getAction(), msg.getName(), msg.getAbsenceType());
    }

    // =========================================================================
    // 내부 — 날짜별 등록/취소
    // =========================================================================

    /**
     * 특정 날짜 부재 이벤트 생성 (1인 1이벤트)
     *
     * <p>이미 동일 이벤트가 있으면 멱등 처리(무시).
     */
    private void applyDate(String calendarId, AbsenceMessage msg, LocalDate date) {
        String summary = buildSummary(msg.getAbsenceType(), msg.getName());

        // 동일 이벤트 중복 체크 (멱등)
        Event existing = findExact(calendarId, msg.getAbsenceType(), msg.getName(), date);
        if (existing != null) {
            log.info("이미 존재하는 부재 이벤트 → 무시: summary={}, date={}", summary, date);
            return;
        }

        Event event = new Event()
                .setSummary(summary)
                .setDescription(msg.getReason())
                .setStart(new EventDateTime().setDate(new DateTime(date.toString())))
                .setEnd(new EventDateTime().setDate(new DateTime(date.plusDays(1).toString())))
                .setTransparency("transparent");

        calendarService.insertCalendarEvent(calendarId, event);
        log.info("부재 이벤트 생성: summary={}, date={}", summary, date);
    }

    /**
     * 특정 날짜 부재 이벤트 취소(삭제)
     *
     * <p>이벤트 없으면 조용히 종료 (DLQ 방지).
     */
    private void cancelDate(String calendarId, AbsenceMessage msg, LocalDate date) {
        Event existing = findExact(calendarId, msg.getAbsenceType(), msg.getName(), date);

        if (existing == null) {
            log.info("취소할 부재 이벤트 없음 → 무시: type={}, name={}, date={}",
                    msg.getAbsenceType(), msg.getName(), date);
            return;
        }

        calendarService.deleteCalendarEvent(calendarId, existing.getId());
        log.info("부재 이벤트 삭제: summary={}, date={}, eventId={}",
                existing.getSummary(), date, existing.getId());
    }

    // =========================================================================
    // 내부 — 이벤트 검색
    // =========================================================================

    /**
     * 특정 날짜에서 이름·유형이 정확히 일치하는 이벤트 검색
     *
     * <p>제목이 "{absenceType}({name})" 으로 정확히 일치하는 이벤트를 반환.
     */
    private Event findExact(
            String calendarId, String absenceType, String name, LocalDate date) {
        String timeMin = date + "T00:00:00+09:00";
        String timeMax = date + "T23:59:59+09:00";
        String expected = buildSummary(absenceType, name);

        List<Event> events = calendarService.listCalendarEvents(
                calendarId, timeMin, timeMax, absenceType);

        return events.stream()
                .filter(e -> expected.equals(e.getSummary()))
                .findFirst()
                .orElse(null);
    }

    // =========================================================================
    // 내부 — 이벤트 제목
    // =========================================================================

    /**
     * 이벤트 제목 생성: "연차(홍길동)"
     */
    private String buildSummary(String absenceType, String name) {
        return absenceType + "(" + name + ")";
    }
}
