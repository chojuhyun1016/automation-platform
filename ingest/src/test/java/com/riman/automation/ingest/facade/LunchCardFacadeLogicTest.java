package com.riman.automation.ingest.facade;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.ingest.payload.LunchCardModalBuilder.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LunchCardFacade 핵심 로직 (상태 판별, 이름 파싱, 요일 매핑) 유닛 테스트.
 * static/package-private 메서드를 직접 테스트한다.
 */
class LunchCardFacadeLogicTest {

  private static Event createEvent(String summary, String date) {
    Event event = new Event().setSummary(summary);
    event.setStart(new EventDateTime().setDate(new DateTime(date)));
    return event;
  }

  /** dateTime (시간 포함) 이벤트 생성 헬퍼. */
  private static Event createDateTimeEvent(String summary, String dateTimeRfc3339) {
    Event event = new Event().setSummary(summary);
    event.setStart(new EventDateTime().setDateTime(new DateTime(dateTimeRfc3339)));
    return event;
  }

  @Nested
  @DisplayName("extractEventDate")
  class ExtractEventDate {

    @Test
    @DisplayName("all-day 이벤트 — date 필드에서 날짜 추출")
    void allDayEvent() {
      Event event = createEvent("점심카드(홍길동)", "2026-04-20");
      assertThat(LunchCardFacade.extractEventDate(event)).isEqualTo("2026-04-20");
    }

    @Test
    @DisplayName("dateTime 이벤트 — KST 시간대에서 날짜 추출")
    void dateTimeEvent_kst() {
      Event event = createDateTimeEvent("점심카드(홍길동)", "2026-04-20T12:00:00+09:00");
      assertThat(LunchCardFacade.extractEventDate(event)).isEqualTo("2026-04-20");
    }

    @Test
    @DisplayName("dateTime 이벤트 (UTC 자정 전) — KST 기준 올바른 날짜 반환")
    void dateTimeEvent_utcMidnight() {
      // UTC 2026-04-19T15:00:00 = KST 2026-04-20T00:00:00
      Event event = createDateTimeEvent("점심카드(홍길동)", "2026-04-19T15:00:00.000Z");
      assertThat(LunchCardFacade.extractEventDate(event)).isEqualTo("2026-04-20");
    }

    @Test
    @DisplayName("start null → 빈 문자열")
    void nullStart() {
      Event event = new Event().setSummary("점심카드(홍길동)");
      assertThat(LunchCardFacade.extractEventDate(event)).isEmpty();
    }

    @Test
    @DisplayName("start 존재하지만 date/dateTime 모두 null → 빈 문자열")
    void emptyStart() {
      Event event = new Event().setSummary("점심카드(홍길동)");
      event.setStart(new EventDateTime());
      assertThat(LunchCardFacade.extractEventDate(event)).isEmpty();
    }
  }

  @Nested
  @DisplayName("extractNameFromSummary")
  class ExtractName {

    @Test
    @DisplayName("정상 — 점심카드(홍길동) → 홍길동")
    void normal() {
      assertThat(LunchCardFacade.extractNameFromSummary("점심카드(홍길동)"))
          .isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이름 없음 — 점심카드 → null")
    void noName() {
      assertThat(LunchCardFacade.extractNameFromSummary("점심카드")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null/empty → null")
    void nullOrEmpty(String input) {
      assertThat(LunchCardFacade.extractNameFromSummary(input)).isNull();
    }

    @Test
    @DisplayName("다른 형식 — [Jira] CCE-123 → null")
    void otherFormat() {
      assertThat(LunchCardFacade.extractNameFromSummary("[Jira] CCE-123 (홍길동)")).isNull();
    }
  }

  @Nested
  @DisplayName("determineStatus")
  class DetermineStatus {

    @Test
    @DisplayName("이벤트 없음 → UNREGISTERED")
    void noEvents_unregistered() {
      assertThat(LunchCardFacade.determineStatus(List.of(), "홍길동"))
          .isEqualTo(Status.UNREGISTERED);
    }

    @Test
    @DisplayName("본인 이벤트 존재 → SELF_REGISTERED")
    void selfEvent_selfRegistered() {
      List<Event> events = List.of(createEvent("점심카드(홍길동)", "2026-04-20"));
      assertThat(LunchCardFacade.determineStatus(events, "홍길동"))
          .isEqualTo(Status.SELF_REGISTERED);
    }

    @Test
    @DisplayName("타인 이벤트만 존재 → OTHER_REGISTERED")
    void otherEvent_otherRegistered() {
      List<Event> events = List.of(createEvent("점심카드(김철수)", "2026-04-20"));
      assertThat(LunchCardFacade.determineStatus(events, "홍길동"))
          .isEqualTo(Status.OTHER_REGISTERED);
    }

    @Test
    @DisplayName("requesterName null + 이벤트 존재 → OTHER_REGISTERED")
    void nullRequester_otherRegistered() {
      List<Event> events = List.of(createEvent("점심카드(김철수)", "2026-04-20"));
      assertThat(LunchCardFacade.determineStatus(events, null))
          .isEqualTo(Status.OTHER_REGISTERED);
    }
  }

  @Nested
  @DisplayName("countEvents")
  class CountEvents {

    @Test
    @DisplayName("빈 목록 → 0")
    void empty() {
      assertThat(LunchCardFacade.countEvents(List.of())).isZero();
    }

    @Test
    @DisplayName("이벤트 3개 → 3")
    void threeEvents() {
      List<Event> events = List.of(
          createEvent("점심카드(A)", "2026-04-20"),
          createEvent("점심카드(B)", "2026-04-21"),
          createEvent("점심카드(C)", "2026-04-22"));
      assertThat(LunchCardFacade.countEvents(events)).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("filterEventsByDate")
  class FilterByDate {

    @Test
    @DisplayName("해당 날짜 이벤트만 필터링")
    void filterCorrectDate() {
      List<Event> events = List.of(
          createEvent("점심카드(A)", "2026-04-20"),
          createEvent("점심카드(B)", "2026-04-21"),
          createEvent("점심카드(C)", "2026-04-20"));

      List<Event> filtered = LunchCardFacade.filterEventsByDate(
          events, LocalDate.of(2026, 4, 20));
      assertThat(filtered).hasSize(2);
    }

    @Test
    @DisplayName("일치 없음 → 빈 리스트")
    void noMatch() {
      List<Event> events = List.of(createEvent("점심카드(A)", "2026-04-21"));
      List<Event> filtered = LunchCardFacade.filterEventsByDate(
          events, LocalDate.of(2026, 4, 20));
      assertThat(filtered).isEmpty();
    }
  }

  @Nested
  @DisplayName("buildDayOfWeekMap")
  class BuildDayOfWeekMap {

    @Test
    @DisplayName("월~금 요일별 사용자 배치")
    void correctDayMapping() {
      // 2026-04-20 = 월요일
      List<Event> events = List.of(
          createEvent("점심카드(홍길동)", "2026-04-20"),  // 월
          createEvent("점심카드(김철수)", "2026-04-20"),  // 월
          createEvent("점심카드(이영희)", "2026-04-22")); // 수

      Map<String, List<String>> map = LunchCardFacade.buildDayOfWeekMap(
          events, LocalDate.of(2026, 4, 20));

      assertThat(map.get("월")).containsExactly("홍길동", "김철수");
      assertThat(map.get("화")).isEmpty();
      assertThat(map.get("수")).containsExactly("이영희");
      assertThat(map.get("목")).isEmpty();
      assertThat(map.get("금")).isEmpty();
    }

    @Test
    @DisplayName("빈 이벤트 → 모든 요일 빈 리스트")
    void emptyEvents() {
      Map<String, List<String>> map = LunchCardFacade.buildDayOfWeekMap(
          List.of(), LocalDate.of(2026, 4, 20));

      assertThat(map).hasSize(5);
      map.values().forEach(users -> assertThat(users).isEmpty());
    }

    @Test
    @DisplayName("dateTime 이벤트도 올바른 요일에 매핑")
    void dateTimeEvents_correctMapping() {
      // 2026-04-20 = 월요일
      List<Event> events = List.of(
          createDateTimeEvent("점심카드(홍길동)", "2026-04-20T12:00:00+09:00"),  // 월
          createDateTimeEvent("점심카드(김철수)", "2026-04-21T09:00:00+09:00")); // 화

      Map<String, List<String>> map = LunchCardFacade.buildDayOfWeekMap(
          events, LocalDate.of(2026, 4, 20));

      assertThat(map.get("월")).containsExactly("홍길동");
      assertThat(map.get("화")).containsExactly("김철수");
      assertThat(map.get("수")).isEmpty();
    }

    @Test
    @DisplayName("dateTime UTC 이벤트 — KST 변환 후 올바른 요일에 매핑")
    void dateTimeUtcEvents_kstConversion() {
      // UTC 2026-04-19T15:00:00Z = KST 2026-04-20T00:00:00 (월요일)
      List<Event> events = List.of(
          createDateTimeEvent("점심카드(홍길동)", "2026-04-19T15:00:00.000Z"));

      Map<String, List<String>> map = LunchCardFacade.buildDayOfWeekMap(
          events, LocalDate.of(2026, 4, 20));

      assertThat(map.get("월")).containsExactly("홍길동");
    }

    @Test
    @DisplayName("all-day + dateTime 혼합 이벤트 매핑")
    void mixedEvents() {
      // 2026-04-20 = 월요일
      List<Event> events = List.of(
          createEvent("점심카드(홍길동)", "2026-04-20"),                          // all-day 월
          createDateTimeEvent("점심카드(김철수)", "2026-04-22T11:30:00+09:00"));  // dateTime 수

      Map<String, List<String>> map = LunchCardFacade.buildDayOfWeekMap(
          events, LocalDate.of(2026, 4, 20));

      assertThat(map.get("월")).containsExactly("홍길동");
      assertThat(map.get("수")).containsExactly("김철수");
    }
  }
}
