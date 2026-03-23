package com.riman.automation.worker.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 재택근무 신청/취소 비즈니스 로직 서비스
 *
 * <pre>
 * 역할: 재택근무 신청/취소 비즈니스 로직 처리
 *
 * 처리 규칙:
 *   신청(apply):
 *     해당 날짜 "재택(" 이벤트 없음  → "재택(조주현)" 신규 생성
 *     해당 날짜 "재택(" 이벤트 있음  → "재택(조주현, 김철수)"로 이름 누적
 *     이미 동일 이름 있음            → 중복 무시 (멱등성 보장)
 *
 *   취소(cancel):
 *     해당 날짜 이벤트 없음           → 조용히 종료 (DLQ 방지)
 *     이름 제거 후 남은 사람 있음     → 제목만 업데이트
 *     이름 제거 후 아무도 없음        → 이벤트 자체 삭제
 *
 * 이벤트 형식: "재택(조주현, 김철수)" / 종일 이벤트 / transparency: transparent
 * </pre>
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

    // =========================================================================
    // 진입점 — RemoteWorkFacade에서 호출
    // =========================================================================

    /**
     * 재택 신청/취소 처리
     *
     * @param action "apply" | "cancel"
     * @param name   한글 이름 (예: 조주현)
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

    // =========================================================================
    // 신청 처리
    // =========================================================================

    private void handleApply(String calendarId, String name, String date) {
        try {
            CalendarService.RemoteWorkCalendarInfo existing =
                    calendarService.findRemoteWorkEvent(calendarId, date);

            if (existing == null) {
                // 해당 날짜 재택 이벤트 없음 → 신규 생성
                createEvent(calendarId, name, date);
                log.info("재택 이벤트 생성 완료: name={}, date={}", name, date);
            } else {
                // 이미 이벤트 있음 → 이름 추가
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

    // =========================================================================
    // 취소 처리
    // =========================================================================

    private void handleCancel(String calendarId, String name, String date) {
        try {
            CalendarService.RemoteWorkCalendarInfo existing =
                    calendarService.findRemoteWorkEvent(calendarId, date);

            if (existing == null) {
                // 취소할 이벤트 없음 → 조용히 종료 (DLQ 방지)
                log.info("취소할 재택 이벤트 없음 (무시): name={}, date={}", name, date);
                return;
            }

            if (!isNameInTitle(existing.summary, name)) {
                // 이름이 이벤트에 없음 → 조용히 종료
                log.info("취소할 이름이 이벤트에 없음 (무시): name={}, title={}", name, existing.summary);
                return;
            }

            String newTitle = removeName(existing.summary, name);

            if (isEventEmpty(newTitle)) {
                // 마지막 취소 → 이벤트 자체 삭제
                calendarService.deleteRemoteWorkEvent(calendarId, existing.eventId);
                log.info("재택 이벤트 삭제 완료 (마지막 취소): name={}, date={}", name, date);
            } else {
                // 남은 사람 있음 → 제목만 업데이트
                calendarService.updateRemoteWorkEventTitle(calendarId, existing.eventId, newTitle);
                log.info("재택 이름 제거: {} → {}", existing.summary, newTitle);
            }
        } catch (Exception e) {
            log.error("재택 취소 처리 실패: name={}, date={}", name, date, e);
            throw new RuntimeException("재택 취소 캘린더 처리 실패", e);
        }
    }

    // =========================================================================
    // 이벤트 생성
    // =========================================================================

    private void createEvent(String calendarId, String name, String date) throws Exception {
        LocalDate d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);

        Event event = new Event()
                .setSummary(TITLE_PREFIX + name + TITLE_SUFFIX)
                .setStart(new EventDateTime().setDate(new DateTime(date)))
                .setEnd(new EventDateTime().setDate(new DateTime(d.plusDays(1).toString())))
                .setTransparency("transparent");

        calendarService.insertRemoteWorkEvent(calendarId, event);
    }

    // =========================================================================
    // 제목 문자열 조작
    // =========================================================================

    /**
     * "재택(조주현)" → "재택(조주현, 김철수)"
     */
    private String appendName(String title, String name) {
        return title.substring(0, title.length() - 1) + ", " + name + TITLE_SUFFIX;
    }

    /**
     * "재택(조주현, 김철수, 박영희)"에서 "김철수" 제거 → "재택(조주현, 박영희)"
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
     * 이름이 이벤트 제목에 포함되어 있는지 확인
     */
    private boolean isNameInTitle(String title, String name) {
        String inner = title.substring(TITLE_PREFIX.length(), title.length() - 1);
        for (String part : inner.split(",\\s*")) {
            if (part.trim().equals(name)) return true;
        }
        return false;
    }

    /**
     * "재택()"처럼 아무도 없는 상태인지 확인
     */
    private boolean isEventEmpty(String title) {
        return title.substring(TITLE_PREFIX.length(), title.length() - 1).trim().isEmpty();
    }

    // =========================================================================
    // 내부 헬퍼
    // =========================================================================

    /**
     * 재택 캘린더 ID 결정
     * config.json > remoteWork.calendar_id → CCE calendar_id → primary 순 폴백
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
