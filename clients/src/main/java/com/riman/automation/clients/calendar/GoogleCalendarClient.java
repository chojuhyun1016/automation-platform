package com.riman.automation.clients.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpResponseException;
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
 * Google Calendar Java SDK 클라이언트.
 * S3에서 서비스 계정 키를 로드하여 Calendar API를 호출하며, 이벤트 CRUD를 담당한다.
 * 이벤트 파싱과 비즈니스 로직은 상위 계층(CalendarCollector)에서 담당한다.
 *
 * 인증: S3의 google-credentials.json -> GoogleCredentials.fromStream() -> CalendarScopes.CALENDAR
 * 토큰 만료 5분 전 자동 갱신되므로 별도 갱신 Lambda가 불필요하다.
 * 생성 비용이 ~1200ms이므로 반드시 static volatile로 캐싱해야 한다.
 */
@Slf4j
public class GoogleCalendarClient {

  private static final String APPLICATION_NAME = "AutomationScheduler";
  private static final String CALENDAR_SCOPE = CalendarScopes.CALENDAR;

  private final Calendar calendarService;

  /**
   * @param credentialsBytes S3에서 읽은 google-credentials.json 원본 바이트
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

  /**
   * 날짜 범위 이벤트를 조회한다.
   * singleEvents=true로 반복 이벤트도 개별 항목으로 전개한다.
   *
   * @param calendarId 캘린더 ID (예: xxx@group.calendar.google.com)
   * @param timeMinRfc3339 조회 시작 (RFC3339, 예: 2026-02-24T00:00:00+09:00)
   * @param timeMaxRfc3339 조회 종료 (RFC3339, 예: 2026-02-28T23:59:59+09:00)
   * @param searchQuery 제목 검색 키워드 (null이면 전체)
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
          .setSingleEvents(true)
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
      throw classifyException("listEvents", "calendarId=" + calendarId, e);
    }
  }

  /**
   * 이벤트를 생성한다.
   *
   * @param calendarId 캘린더 ID
   * @param event 생성할 이벤트 객체
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
      throw classifyException("insertEvent", "calendarId=" + calendarId, e);
    }
  }

  /**
   * 이벤트를 수정한다 (전체 교체).
   *
   * @param calendarId 캘린더 ID
   * @param eventId 수정할 이벤트 ID
   * @param event 수정된 이벤트 객체
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
      throw classifyException("updateEvent", "eventId=" + eventId, e);
    }
  }

  /**
   * 이벤트를 삭제한다.
   *
   * @param calendarId 캘린더 ID
   * @param eventId 삭제할 이벤트 ID
   */
  public void deleteEvent(String calendarId, String eventId) {
    try {
      calendarService.events().delete(calendarId, eventId).execute();
      log.info("[GoogleCalendarClient] deleteEvent: id={}", eventId);
    } catch (ExternalApiException | ExternalApiClientException e) {
      throw e;
    } catch (Exception e) {
      throw classifyException("deleteEvent", "eventId=" + eventId, e);
    }
  }

  /**
   * Google SDK 예외를 프로젝트 예외로 변환한다.
   * HttpResponseException(4xx/5xx)은 statusCode를 보존하여 ExternalApiException으로,
   * 그 외는 ExternalApiClientException으로 변환한다.
   * 429(Rate Limit)는 상위 계층에서 쿼타 초과 알림을 트리거할 수 있도록 statusCode를 유지한다.
   */
  private static RuntimeException classifyException(String operation, String context, Exception e) {
    if (e instanceof HttpResponseException httpEx) {
      int statusCode = httpEx.getStatusCode();
      String detail = operation + " HTTP " + statusCode + ": " + context;
      if (statusCode == 429) {
        log.warn("[GoogleCalendarClient] Calendar API 쿼타 초과 (429): {}", context);
      }
      return new ExternalApiException("GoogleCalendar", statusCode, detail);
    }
    return new ExternalApiClientException("GoogleCalendar",
        operation + " 실패: " + context, e);
  }
}
