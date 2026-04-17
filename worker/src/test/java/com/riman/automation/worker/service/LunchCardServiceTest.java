package com.riman.automation.worker.service;

import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LunchCardServiceTest {

    @Mock
    private CalendarService calendarService;

    private LunchCardService lunchCardService;
    private AutoCloseable mocks;

    private static final String CALENDAR_ID = "test-calendar@group.calendar.google.com";

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        lunchCardService = new LunchCardService(calendarService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // =========================================================================
    // findLunchCardEvents — 날짜 범위 조회
    // =========================================================================

    @Nested
    @DisplayName("findLunchCardEvents — 날짜별 이벤트 조회")
    class FindEventsTest {

        @Test
        @DisplayName("지정 날짜의 점심카드 이벤트 목록을 반환한다")
        void findLunchCardEvents_returnsEvents() {
            Event event = new Event().setSummary("점심카드(홍길동)");
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<Event> result = lunchCardService.findLunchCardEvents(CALENDAR_ID, "2024-06-10");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSummary()).isEqualTo("점심카드(홍길동)");
        }

        @Test
        @DisplayName("이벤트가 없으면 빈 리스트를 반환한다")
        void findLunchCardEvents_empty_returnsEmptyList() {
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(Collections.emptyList());

            List<Event> result = lunchCardService.findLunchCardEvents(CALENDAR_ID, "2024-06-10");

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // filterByLunchCardSummary — Java 필터링
    // =========================================================================

    @Nested
    @DisplayName("filterByLunchCardSummary — Java 필터링")
    class FilterTest {

        @Test
        @DisplayName("점심카드 이벤트만 필터링하고 다른 이벤트는 제외한다")
        void filterByLunchCardSummary_filtersCorrectly() {
            List<Event> mixed = List.of(
                    new Event().setSummary("점심카드(홍길동)"),
                    new Event().setSummary("회의"),
                    new Event().setSummary("점심카드(김철수)"),
                    new Event().setSummary("재택(박영희)")
            );

            List<Event> result = LunchCardService.filterByLunchCardSummary(mixed);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(Event::getSummary)
                    .containsExactly("점심카드(홍길동)", "점심카드(김철수)");
        }

        @Test
        @DisplayName("빈 리스트를 입력하면 빈 리스트를 반환한다")
        void filterByLunchCardSummary_emptyList_returnsEmpty() {
            List<Event> result = LunchCardService.filterByLunchCardSummary(Collections.emptyList());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null summary 이벤트는 제외한다")
        void filterByLunchCardSummary_nullSummary_excluded() {
            List<Event> events = List.of(
                    new Event().setSummary(null),
                    new Event().setSummary("점심카드(홍길동)")
            );

            List<Event> result = LunchCardService.filterByLunchCardSummary(events);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSummary()).isEqualTo("점심카드(홍길동)");
        }
    }

    // =========================================================================
    // findLunchCardEvents + filterByLunchCardSummary 통합
    // =========================================================================

    @Nested
    @DisplayName("findLunchCardEvents — searchQuery=null + Java 필터링 통합")
    class FindEventsFilterIntegrationTest {

        @Test
        @DisplayName("API가 혼합 이벤트를 반환해도 점심카드만 결과에 포함된다")
        void findLunchCardEvents_mixedResults_filtersToLunchCardOnly() {
            List<Event> mixedFromApi = List.of(
                    new Event().setSummary("점심카드(홍길동)"),
                    new Event().setSummary("팀 회의"),
                    new Event().setSummary("점심카드(김철수)")
            );
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(mixedFromApi);

            List<Event> result = lunchCardService.findLunchCardEvents(CALENDAR_ID, "2024-06-10");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(Event::getSummary)
                    .containsExactly("점심카드(홍길동)", "점심카드(김철수)");
        }
    }

    // =========================================================================
    // findLunchCardEvent — 특정 사용자 이벤트 조회
    // =========================================================================

    @Nested
    @DisplayName("findLunchCardEvent — 특정 사용자 조회")
    class FindEventTest {

        @Test
        @DisplayName("해당 날짜에 이름이 일치하는 이벤트를 반환한다")
        void findLunchCardEvent_matchingName_returnsEvent() {
            Event event = new Event().setSummary("점심카드(홍길동)").setId("evt-1");
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            Event result = lunchCardService.findLunchCardEvent(CALENDAR_ID, "홍길동", "2024-06-10");

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("evt-1");
        }

        @Test
        @DisplayName("이름이 일치하지 않으면 null을 반환한다")
        void findLunchCardEvent_noMatch_returnsNull() {
            Event event = new Event().setSummary("점심카드(김철수)");
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            Event result = lunchCardService.findLunchCardEvent(CALENDAR_ID, "홍길동", "2024-06-10");

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // applyLunchCard — 신청
    // =========================================================================

    @Nested
    @DisplayName("applyLunchCard — 신청 처리")
    class ApplyTest {

        @Test
        @DisplayName("기존 이벤트가 없으면 신규 생성한다")
        void applyLunchCard_noExisting_createsEvent() {
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(Collections.emptyList());
            when(calendarService.insertCalendarEvent(eq(CALENDAR_ID), any(Event.class)))
                    .thenReturn(new Event().setId("new-evt"));

            lunchCardService.applyLunchCard(CALENDAR_ID, "홍길동", "2024-06-10");

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(calendarService).insertCalendarEvent(eq(CALENDAR_ID), captor.capture());

            Event created = captor.getValue();
            assertThat(created.getSummary()).isEqualTo("점심카드(홍길동)");
            assertThat(created.getStart().getDate().toString()).isEqualTo("2024-06-10");
            assertThat(created.getEnd().getDate().toString()).isEqualTo("2024-06-11");
            assertThat(created.getTransparency()).isEqualTo("transparent");
        }

        @Test
        @DisplayName("기존 이벤트가 있으면 멱등 처리 (중복 생성하지 않음)")
        void applyLunchCard_existing_skipsCreate() {
            Event existing = new Event().setSummary("점심카드(홍길동)").setId("existing-evt");
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(existing));

            lunchCardService.applyLunchCard(CALENDAR_ID, "홍길동", "2024-06-10");

            verify(calendarService, never()).insertCalendarEvent(anyString(), any(Event.class));
        }
    }

    // =========================================================================
    // cancelLunchCard — 취소
    // =========================================================================

    @Nested
    @DisplayName("cancelLunchCard — 취소 처리")
    class CancelTest {

        @Test
        @DisplayName("기존 이벤트가 있으면 삭제한다")
        void cancelLunchCard_existing_deletesEvent() {
            Event existing = new Event().setSummary("점심카드(홍길동)").setId("evt-to-delete");
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(existing));

            lunchCardService.cancelLunchCard(CALENDAR_ID, "홍길동", "2024-06-10");

            verify(calendarService).deleteCalendarEvent(CALENDAR_ID, "evt-to-delete");
        }

        @Test
        @DisplayName("기존 이벤트가 없으면 조용히 종료한다 (DLQ 방지)")
        void cancelLunchCard_noExisting_skips() {
            when(calendarService.listCalendarEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(Collections.emptyList());

            lunchCardService.cancelLunchCard(CALENDAR_ID, "홍길동", "2024-06-10");

            verify(calendarService, never()).deleteCalendarEvent(anyString(), anyString());
        }
    }
}
