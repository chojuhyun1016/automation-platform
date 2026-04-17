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
  }
}
