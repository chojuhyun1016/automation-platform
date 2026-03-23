package com.riman.automation.clients.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;

/**
 * Google Calendar Java SDK 클라이언트
 *
 * <p><b>인증 방식 — worker의 CalendarService와 완전히 동일:</b>
 * <ol>
 *   <li>S3에서 google-credentials.json(서비스 계정 키) 바이트 배열 로드</li>
 *   <li>{@code GoogleCredentials.fromStream()} → CalendarScopes.CALENDAR scope 부여</li>
 *   <li>{@code HttpCredentialsAdapter}로 Calendar 클라이언트 빌드</li>
 * </ol>
 *
 * <p><b>책임:</b> Calendar API 호출 + {@link Event}/{@link Events} 반환.
 * 이벤트 파싱·비즈니스 로직은 상위 계층(CalendarCollector)에서 담당.
 *
 * <p><b>토큰 만료 자동 처리:</b>
 * {@code GoogleCredentials}는 만료 5분 전 자동 갱신 — 별도 갱신 Lambda 불필요.
 *
 * <p><b>필요 환경변수 (SchedulerHandler가 S3 로드 후 생성자에 주입):</b>
 * <pre>
 *   GOOGLE_CALENDAR_CREDENTIALS_BUCKET  — 서비스 계정 키 파일이 있는 S3 버킷
 *   GOOGLE_CALENDAR_CREDENTIALS_KEY     — S3 내 키 파일 경로 (예: google-credentials.json)
 * </pre>
 */
@Slf4j
public class GoogleCalendarClient {

    private static final String APPLICATION_NAME = "AutomationScheduler";

    /**
     * worker CalendarService와 동일한 스코프
     */
    private static final String CALENDAR_SCOPE = CalendarScopes.CALENDAR;

    private final Calendar calendarService;

    // =========================================================================
    // 생성자 — worker CalendarService.init() 로직을 그대로 이식
    // =========================================================================

    /**
     * @param credentialsBytes S3에서 읽은 google-credentials.json 원본 바이트
     *                         (SchedulerHandler의 loadGoogleCredentials()가 전달)
     */
    public GoogleCalendarClient(byte[] credentialsBytes) {
        try {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(credentialsBytes))
                    .createScoped(Collections.singleton(CALENDAR_SCOPE));

            this.calendarService = new Calendar.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            log.info("[GoogleCalendarClient] SDK 초기화 완료 (서비스 계정 방식)");

        } catch (Exception e) {
            throw new ConfigException("GoogleCalendarClient SDK 초기화 실패", e);
        }
    }

    // =========================================================================
    // 이벤트 목록 조회
    // =========================================================================

    /**
     * 날짜 범위 이벤트 조회
     *
     * <p>worker CalendarService.findRemoteWorkEvent()와 동일한 방식.
     * singleEvents=true 로 반복 이벤트도 개별 항목으로 전개.
     *
     * @param calendarId     캘린더 ID (예: xxx@group.calendar.google.com)
     * @param timeMinRfc3339 조회 시작 (RFC3339, 예: 2026-02-24T00:00:00+09:00)
     * @param timeMaxRfc3339 조회 종료 (RFC3339, 예: 2026-02-28T23:59:59+09:00)
     * @param searchQuery    제목 검색 키워드 (null이면 전체)
     * @return 이벤트 목록 (없으면 빈 리스트)
     */
    public List<Event> listEvents(String calendarId,
                                  String timeMinRfc3339,
                                  String timeMaxRfc3339,
                                  String searchQuery) {
        try {
            log.info("[GoogleCalendarClient] listEvents: calendar={}, q={}", calendarId, searchQuery);

            Calendar.Events.List request = calendarService.events()
                    .list(calendarId)
                    .setTimeMin(new DateTime(timeMinRfc3339))
                    .setTimeMax(new DateTime(timeMaxRfc3339))
                    .setSingleEvents(true)     // 반복 이벤트 전개
                    .setOrderBy("startTime")
                    .setShowDeleted(false)
                    .setMaxResults(250);

            if (searchQuery != null && !searchQuery.isBlank()) {
                request.setQ(searchQuery);
            }

            Events result = request.execute();
            List<Event> items = result.getItems();

            log.info("[GoogleCalendarClient] listEvents 완료: {}건",
                    items == null ? 0 : items.size());
            return items == null ? List.of() : items;

        } catch (ExternalApiException | ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException("GoogleCalendar",
                    "listEvents 실패: calendarId=" + calendarId, e);
        }
    }

    // =========================================================================
    // 이벤트 생성
    // =========================================================================

    /**
     * 이벤트 생성
     *
     * @param calendarId 캘린더 ID
     * @param event      생성할 이벤트 객체
     * @return 생성된 이벤트 (Google API 응답)
     */
    public Event insertEvent(String calendarId, Event event) {
        try {
            Event created = calendarService.events().insert(calendarId, event).execute();
            log.info("[GoogleCalendarClient] insertEvent: id={}, summary={}",
                    created.getId(), created.getSummary());
            return created;
        } catch (ExternalApiException | ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException("GoogleCalendar",
                    "insertEvent 실패: calendarId=" + calendarId, e);
        }
    }

    // =========================================================================
    // 이벤트 수정
    // =========================================================================

    /**
     * 이벤트 수정 (전체 교체)
     *
     * @param calendarId 캘린더 ID
     * @param eventId    수정할 이벤트 ID
     * @param event      수정된 이벤트 객체
     * @return 수정된 이벤트 (Google API 응답)
     */
    public Event updateEvent(String calendarId, String eventId, Event event) {
        try {
            Event updated = calendarService.events()
                    .update(calendarId, eventId, event).execute();
            log.info("[GoogleCalendarClient] updateEvent: id={}, summary={}",
                    updated.getId(), updated.getSummary());
            return updated;
        } catch (ExternalApiException | ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException("GoogleCalendar",
                    "updateEvent 실패: eventId=" + eventId, e);
        }
    }

    // =========================================================================
    // 이벤트 삭제
    // =========================================================================

    /**
     * 이벤트 삭제
     *
     * @param calendarId 캘린더 ID
     * @param eventId    삭제할 이벤트 ID
     */
    public void deleteEvent(String calendarId, String eventId) {
        try {
            calendarService.events().delete(calendarId, eventId).execute();
            log.info("[GoogleCalendarClient] deleteEvent: id={}", eventId);
        } catch (ExternalApiException | ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiClientException("GoogleCalendar",
                    "deleteEvent 실패: eventId=" + eventId, e);
        }
    }
}
