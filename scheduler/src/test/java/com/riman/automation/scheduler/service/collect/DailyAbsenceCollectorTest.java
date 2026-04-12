package com.riman.automation.scheduler.service.collect;

import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.common.code.WorkStatusCode;
import com.riman.automation.scheduler.dto.report.DailyReportData.AbsenceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyAbsenceCollectorTest {

    @Mock
    private GoogleCalendarClient calendarClient;

    private DailyAbsenceCollector collector;

    private static final String CALENDAR_ID = "absence@group.calendar.google.com";
    private static final String TICKET_CALENDAR_ID = "ticket@group.calendar.google.com";

    @BeforeEach
    void setUp() {
        collector = new DailyAbsenceCollector(calendarClient);
    }

    // =========================================================================
    // calendarId 검증
    // =========================================================================

    @Nested
    @DisplayName("calendarId 검증")
    class CalendarIdValidationTest {

        @Test
        @DisplayName("calendarId null → 빈 리스트")
        void nullCalendarId() {
            List<AbsenceItem> result = collector.collect(null, TICKET_CALENDAR_ID, LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
            verifyNoInteractions(calendarClient);
        }

        @Test
        @DisplayName("calendarId blank → 빈 리스트")
        void blankCalendarId() {
            List<AbsenceItem> result = collector.collect("  ", TICKET_CALENDAR_ID, LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // 2개 캘린더 병합
    // =========================================================================

    @Nested
    @DisplayName("2개 캘린더 병합")
    class MergeCalendarsTest {

        @Test
        @DisplayName("ticketCalendarId가 calendarId와 동일 → 단일 조회")
        void sameCalendarId() {
            Event event = createEvent("evt-1", "재택(조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            collector.collect(CALENDAR_ID, CALENDAR_ID, LocalDate.of(2026, 4, 13));

            verify(calendarClient, times(1)).listEvents(anyString(), anyString(), anyString(), isNull());
        }

        @Test
        @DisplayName("ticketCalendarId null → 단일 조회")
        void nullTicketCalendarId() {
            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of());

            collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            verify(calendarClient, times(1)).listEvents(anyString(), anyString(), anyString(), isNull());
        }

        @Test
        @DisplayName("서로 다른 캘린더 → 2회 조회 + 이벤트 ID 중복 제거")
        void differentCalendars() {
            Event event1 = createEvent("evt-1", "재택(조주현)", "2026-04-13");
            Event event2 = createEvent("evt-2", "재택(홍길동)", "2026-04-13");
            Event duplicate = createEvent("evt-1", "재택(조주현)", "2026-04-13"); // 같은 ID

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event1));
            when(calendarClient.listEvents(eq(TICKET_CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(duplicate, event2));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, TICKET_CALENDAR_ID, LocalDate.of(2026, 4, 13));

            // evt-1 중복 제거 → 조주현 + 홍길동 = 2건
            assertThat(result).hasSize(2);
            verify(calendarClient, times(2)).listEvents(anyString(), anyString(), anyString(), isNull());
        }
    }

    // =========================================================================
    // 재택 파싱
    // =========================================================================

    @Nested
    @DisplayName("재택 이벤트 파싱")
    class RemoteEventTest {

        @Test
        @DisplayName("재택(조주현) → REMOTE 1건")
        void singleRemote() {
            Event event = createEvent("evt-1", "재택(조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMemberName()).isEqualTo("조주현");
            assertThat(result.get(0).getWorkStatus()).isEqualTo(WorkStatusCode.REMOTE);
        }

        @Test
        @DisplayName("재택(조주현, 홍길동) → 2건 개별 생성")
        void multipleRemote() {
            Event event = createEvent("evt-1", "재택(조주현, 홍길동)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AbsenceItem::getMemberName)
                    .containsExactly("조주현", "홍길동");
        }

        @Test
        @DisplayName("오늘 날짜 → today=true")
        void todayFlag() {
            LocalDate baseDate = LocalDate.of(2026, 4, 13);
            Event event = createEvent("evt-1", "재택(조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, baseDate);

            assertThat(result.get(0).isToday()).isTrue();
        }
    }

    // =========================================================================
    // AbsenceTypeCode 파싱
    // =========================================================================

    @Nested
    @DisplayName("AbsenceTypeCode 파싱")
    class AbsenceTypeEventTest {

        @Test
        @DisplayName("연차(홍길동) → displayName=홍길동(연차), UNKNOWN 상태")
        void annualLeave() {
            Event event = createEvent("evt-1", "연차(홍길동)", "2026-04-14");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMemberName()).isEqualTo("홍길동(연차)");
            assertThat(result.get(0).getWorkStatus()).isEqualTo(WorkStatusCode.UNKNOWN);
        }

        @Test
        @DisplayName("오전 반차(김철수) → 김철수(오전 반차)")
        void amHalf() {
            Event event = createEvent("evt-1", "오전 반차(김철수)", "2026-04-14");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMemberName()).isEqualTo("김철수(오전 반차)");
        }
    }

    // =========================================================================
    // WorkStatusCode 감지
    // =========================================================================

    @Nested
    @DisplayName("WorkStatusCode 키워드 감지")
    class WorkStatusEventTest {

        @Test
        @DisplayName("외근(박영희) → BUSINESS_TRIP")
        void businessTrip() {
            Event event = createEvent("evt-1", "외근(박영희)", "2026-04-14");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event));

            List<AbsenceItem> result = collector.collect(CALENDAR_ID, null, LocalDate.of(2026, 4, 13));

            // "외근(박영희)"는 AbsenceTypeCode에 없으므로 WorkStatusCode로 감지
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getWorkStatus()).isEqualTo(WorkStatusCode.BUSINESS_TRIP);
        }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private static Event createEvent(String id, String title, String dateStr) {
        Event event = new Event();
        event.setId(id);
        event.setSummary(title);
        event.setStart(new EventDateTime()
                .setDate(new com.google.api.client.util.DateTime(dateStr)));
        return event;
    }
}
