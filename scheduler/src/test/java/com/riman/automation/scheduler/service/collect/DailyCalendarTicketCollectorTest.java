package com.riman.automation.scheduler.service.collect;

import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.scheduler.dto.report.DailyReportData.TicketItem;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyCalendarTicketCollectorTest {

    @Mock
    private GoogleCalendarClient calendarClient;

    @Mock
    private JiraClient jiraClient;

    private DailyCalendarTicketCollector collector;

    private static final String CALENDAR_ID = "test-calendar@group.calendar.google.com";
    private static final String JIRA_BASE_URL = "https://riman-it.atlassian.net";

    @BeforeEach
    void setUp() {
        collector = new DailyCalendarTicketCollector(calendarClient, JIRA_BASE_URL, jiraClient);
    }

    // =========================================================================
    // collectForMember
    // =========================================================================

    @Nested
    @DisplayName("collectForMember")
    class CollectForMemberTest {

        @Test
        @DisplayName("calendarId 미설정 → 빈 리스트")
        void emptyCalendarId() {
            TeamMember member = createMember("조주현");

            List<TicketItem> result = collector.collectForMember(null, member, LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
            verifyNoInteractions(calendarClient);
        }

        @Test
        @DisplayName("calendarId blank → 빈 리스트")
        void blankCalendarId() {
            TeamMember member = createMember("조주현");

            List<TicketItem> result = collector.collectForMember("  ", member, LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("이슈 키 없는 이벤트 → 건너뜀")
        void noIssueKey() {
            TeamMember member = createMember("조주현");
            Event event = createEvent("evt-1", "일반 이벤트", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), eq("조주현")))
                    .thenReturn(List.of(event));

            List<TicketItem> result = collector.collectForMember(CALENDAR_ID, member, LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("정상 이벤트 수집 → TicketItem 생성")
        void normalEvent() {
            TeamMember member = createMember("조주현");
            member.setJiraAccountId("712020:abc");

            Event event = createEvent("evt-1", "[Jira] CCE-123 (조주현)", "2026-04-13");
            event.setDescription("Status: In Progress\nTitle: 로그인 버그");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), eq("조주현")))
                    .thenReturn(List.of(event));

            List<TicketItem> result = collector.collectForMember(CALENDAR_ID, member, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(1);
            TicketItem item = result.get(0);
            assertThat(item.getIssueKey()).isEqualTo("CCE-123");
            assertThat(item.getProjectKey()).isEqualTo("CCE");
            assertThat(item.getAssigneeName()).isEqualTo("조주현");
            assertThat(item.getSummary()).isEqualTo("로그인 버그");
            assertThat(item.getStatus()).isEqualTo(JiraStatusCode.IN_PROGRESS);
            assertThat(item.getUrl()).isEqualTo("https://riman-it.atlassian.net/browse/CCE-123");
        }

        @Test
        @DisplayName("이모지 우선순위 감지: 🔴 → HIGHEST")
        void emojiPriority() {
            TeamMember member = createMember("조주현");

            Event event = createEvent("evt-1", "🔴 [Jira] CCE-456 (조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), eq("조주현")))
                    .thenReturn(List.of(event));

            List<TicketItem> result = collector.collectForMember(CALENDAR_ID, member, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPriority()).isEqualTo(JiraPriorityCode.HIGHEST);
        }

        @Test
        @DisplayName("1차 정렬: due date 가까운 순")
        void sortByDueDate() {
            TeamMember member = createMember("조주현");

            // 동일 priority(MEDIUM), due date만 다름
            Event nearEvent = createEvent("evt-1", "[Jira] CCE-100 (조주현)", "2026-04-13");
            Event farEvent = createEvent("evt-2", "[Jira] CCE-200 (조주현)", "2026-04-15");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), eq("조주현")))
                    .thenReturn(List.of(farEvent, nearEvent));

            List<TicketItem> result = collector.collectForMember(CALENDAR_ID, member, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getIssueKey()).isEqualTo("CCE-100"); // due 4/13
            assertThat(result.get(1).getIssueKey()).isEqualTo("CCE-200"); // due 4/15
        }

        @Test
        @DisplayName("2차 정렬: 동일 due date에서 priority 높은 순")
        void sortByPriorityWhenSameDueDate() {
            TeamMember member = createMember("조주현");

            // 동일 due date, priority만 다름: LOW(🟢) vs HIGHEST(🔴)
            Event lowPriority = createEvent("evt-1", "🟢 [Jira] CCE-100 (조주현)", "2026-04-13");
            Event highPriority = createEvent("evt-2", "🔴 [Jira] CCE-200 (조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), eq("조주현")))
                    .thenReturn(List.of(lowPriority, highPriority));

            List<TicketItem> result = collector.collectForMember(CALENDAR_ID, member, LocalDate.of(2026, 4, 13));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getPriority()).isEqualTo(JiraPriorityCode.HIGHEST); // CCE-200
            assertThat(result.get(1).getPriority()).isEqualTo(JiraPriorityCode.LOW);     // CCE-100
        }
    }

    // =========================================================================
    // collectAllMembers
    // =========================================================================

    @Nested
    @DisplayName("collectAllMembers")
    class CollectAllMembersTest {

        @Test
        @DisplayName("calendarId 미설정 → 빈 맵")
        void emptyCalendarId() {
            Map<TeamMember, List<TicketItem>> result =
                    collector.collectAllMembers(null, List.of(), LocalDate.of(2026, 4, 13));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("팀원별 분류 정확성")
        void memberClassification() {
            TeamMember member1 = createMember("조주현");
            TeamMember member2 = createMember("홍길동");

            Event event1 = createEvent("evt-1", "[Jira] CCE-100 (조주현)", "2026-04-13");
            Event event2 = createEvent("evt-2", "[Jira] CCE-200 (홍길동)", "2026-04-14");

            // 금주/차주 조회
            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event1, event2))        // 금주
                    .thenReturn(List.of());                      // 과거 미완료

            Map<TeamMember, List<TicketItem>> result =
                    collector.collectAllMembers(CALENDAR_ID, List.of(member1, member2), LocalDate.of(2026, 4, 13));

            assertThat(result.get(member1)).hasSize(1);
            assertThat(result.get(member1).get(0).getIssueKey()).isEqualTo("CCE-100");
            assertThat(result.get(member2)).hasSize(1);
            assertThat(result.get(member2).get(0).getIssueKey()).isEqualTo("CCE-200");
        }

        @Test
        @DisplayName("이벤트 ID 중복 제거")
        void deduplication() {
            TeamMember member = createMember("조주현");

            Event event = createEvent("same-id", "[Jira] CCE-100 (조주현)", "2026-04-13");
            Event duplicateEvent = createEvent("same-id", "[Jira] CCE-100 (조주현)", "2026-04-13");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event))          // 금주
                    .thenReturn(List.of(duplicateEvent)); // 과거 (같은 ID)

            Map<TeamMember, List<TicketItem>> result =
                    collector.collectAllMembers(CALENDAR_ID, List.of(member), LocalDate.of(2026, 4, 13));

            assertThat(result.get(member)).hasSize(1);
        }

        @Test
        @DisplayName("jiraClient null → Jira 재검증 건너뜀")
        void noJiraClient() {
            DailyCalendarTicketCollector collectorNoJira =
                    new DailyCalendarTicketCollector(calendarClient, JIRA_BASE_URL, null);

            TeamMember member = createMember("조주현");
            Event event = createEvent("evt-1", "[Jira] CCE-100 (조주현)", "2026-04-01");

            when(calendarClient.listEvents(eq(CALENDAR_ID), anyString(), anyString(), isNull()))
                    .thenReturn(List.of(event))
                    .thenReturn(List.of());

            Map<TeamMember, List<TicketItem>> result =
                    collectorNoJira.collectAllMembers(CALENDAR_ID, List.of(member), LocalDate.of(2026, 4, 13));

            assertThat(result.get(member)).hasSize(1);
            verifyNoInteractions(jiraClient);
        }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private static TeamMember createMember(String name) {
        TeamMember m = new TeamMember();
        m.setName(name);
        return m;
    }

    private static Event createEvent(String id, String title, String dateStr) {
        Event event = new Event();
        event.setId(id);
        event.setSummary(title);
        event.setStart(new EventDateTime()
                .setDate(new com.google.api.client.util.DateTime(dateStr)));
        return event;
    }
}
