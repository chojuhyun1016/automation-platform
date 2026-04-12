package com.riman.automation.scheduler.service.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarTicketParserTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Mock
    private JiraClient jiraClient;

    // =========================================================================
    // extractIssueKey
    // =========================================================================

    @Nested
    @DisplayName("extractIssueKey")
    class ExtractIssueKeyTest {

        @Test
        @DisplayName("형식1: [Jira] CCE-2326 (조주현) → CCE-2326")
        void format1() {
            String result = CalendarTicketParser.extractIssueKey("[Jira] CCE-2326 (조주현)");
            assertThat(result).isEqualTo("CCE-2326");
        }

        @Test
        @DisplayName("형식2: [CCE-123] 로그인 버튼 오류 (홍길동) → CCE-123")
        void format2() {
            String result = CalendarTicketParser.extractIssueKey("[CCE-123] 로그인 버튼 오류 (홍길동)");
            assertThat(result).isEqualTo("CCE-123");
        }

        @Test
        @DisplayName("이슈 키 없는 제목 → null")
        void noKey() {
            String result = CalendarTicketParser.extractIssueKey("일반 이벤트 제목");
            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // extractProjectKey
    // =========================================================================

    @Nested
    @DisplayName("extractProjectKey")
    class ExtractProjectKeyTest {

        @Test
        @DisplayName("CCE-123 → CCE")
        void normal() {
            assertThat(CalendarTicketParser.extractProjectKey("CCE-123")).isEqualTo("CCE");
        }

        @Test
        @DisplayName("null → 빈 문자열")
        void nullInput() {
            assertThat(CalendarTicketParser.extractProjectKey(null)).isEmpty();
        }

        @Test
        @DisplayName("하이픈 없는 문자열 → 빈 문자열")
        void noDash() {
            assertThat(CalendarTicketParser.extractProjectKey("CCE")).isEmpty();
        }
    }

    // =========================================================================
    // parseAssigneeNames
    // =========================================================================

    @Nested
    @DisplayName("parseAssigneeNames")
    class ParseAssigneeNamesTest {

        @Test
        @DisplayName("단일 담당자: (조주현) → [조주현]")
        void singleAssignee() {
            List<String> names = CalendarTicketParser.parseAssigneeNames("[Jira] CCE-123 (조주현)");
            assertThat(names).containsExactly("조주현");
        }

        @Test
        @DisplayName("복수 담당자: (홍길동, 김철수) → [홍길동, 김철수]")
        void multipleAssignees() {
            List<String> names = CalendarTicketParser.parseAssigneeNames("[CCE-123] 제목 (홍길동, 김철수)");
            assertThat(names).containsExactly("홍길동", "김철수");
        }

        @Test
        @DisplayName("담당자 없는 제목 → 빈 리스트")
        void noAssignee() {
            List<String> names = CalendarTicketParser.parseAssigneeNames("[Jira] CCE-123");
            assertThat(names).isEmpty();
        }

        @Test
        @DisplayName("null 제목 → 빈 리스트")
        void nullTitle() {
            List<String> names = CalendarTicketParser.parseAssigneeNames(null);
            assertThat(names).isEmpty();
        }
    }

    // =========================================================================
    // resolveAssigneeName
    // =========================================================================

    @Nested
    @DisplayName("resolveAssigneeName")
    class ResolveAssigneeNameTest {

        @Test
        @DisplayName("팀원 매칭 성공 → 팀원 이름 반환")
        void matchFound() {
            TeamMember member = new TeamMember();
            member.setName("조주현");

            String result = CalendarTicketParser.resolveAssigneeName(
                    List.of("조주현"), List.of(member));
            assertThat(result).isEqualTo("조주현");
        }

        @Test
        @DisplayName("매칭 실패 → 첫 번째 이름 반환")
        void noMatch() {
            TeamMember member = new TeamMember();
            member.setName("박영희");

            String result = CalendarTicketParser.resolveAssigneeName(
                    List.of("알 수 없는 이름"), List.of(member));
            assertThat(result).isEqualTo("알 수 없는 이름");
        }

        @Test
        @DisplayName("빈 리스트 → 미배정")
        void emptyList() {
            String result = CalendarTicketParser.resolveAssigneeName(
                    List.of(), List.of());
            assertThat(result).isEqualTo("미배정");
        }
    }

    // =========================================================================
    // extractSummary
    // =========================================================================

    @Nested
    @DisplayName("extractSummary")
    class ExtractSummaryTest {

        @Test
        @DisplayName("description에 Title 라인 → Title 값 우선")
        void titleFromDescription() {
            Event event = new Event()
                    .setSummary("[Jira] CCE-123 (조주현)")
                    .setDescription("Status: In Progress\nTitle: 로그인 버그 수정");

            String result = CalendarTicketParser.extractSummary(event, "[Jira] CCE-123 (조주현)");
            assertThat(result).isEqualTo("로그인 버그 수정");
        }

        @Test
        @DisplayName("Title 라인 없음, 정제 후 빈 문자열 → 원본 제목 반환")
        void cleanedTitleFallback() {
            Event event = new Event().setSummary("🔴 [Jira] CCE-123 (조주현)");

            String result = CalendarTicketParser.extractSummary(event, "🔴 [Jira] CCE-123 (조주현)");
            // 이슈키+담당자+이모지 제거 후 빈 문자열이면 원본 title 반환
            assertThat(result).isEqualTo("🔴 [Jira] CCE-123 (조주현)");
        }

        @Test
        @DisplayName("Title 라인 없음, 정제 후 텍스트 남음 → 정제된 텍스트")
        void cleanedTitleWithRemainder() {
            Event event = new Event().setSummary("[CCE-123] 로그인 오류 수정 (홍길동)");

            String result = CalendarTicketParser.extractSummary(event, "[CCE-123] 로그인 오류 수정 (홍길동)");
            assertThat(result).isEqualTo("로그인 오류 수정");
        }

        @Test
        @DisplayName("description null → 제목 정제")
        void nullDescription() {
            Event event = new Event().setSummary("[CCE-123] 버그 리포트 (홍길동)");

            String result = CalendarTicketParser.extractSummary(event, "[CCE-123] 버그 리포트 (홍길동)");
            assertThat(result).isEqualTo("버그 리포트");
        }
    }

    // =========================================================================
    // detectStatus
    // =========================================================================

    @Nested
    @DisplayName("detectStatus")
    class DetectStatusTest {

        @Test
        @DisplayName("Status: Done → DONE")
        void done() {
            assertThat(CalendarTicketParser.detectStatus("Status: Done\nPriority: High"))
                    .isEqualTo(JiraStatusCode.DONE);
        }

        @Test
        @DisplayName("Status: In Progress → IN_PROGRESS")
        void inProgress() {
            assertThat(CalendarTicketParser.detectStatus("Status: In Progress"))
                    .isEqualTo(JiraStatusCode.IN_PROGRESS);
        }

        @Test
        @DisplayName("Status 라인 없음 → IN_PROGRESS (fallback)")
        void noStatusLine() {
            assertThat(CalendarTicketParser.detectStatus("Priority: High"))
                    .isEqualTo(JiraStatusCode.IN_PROGRESS);
        }

        @Test
        @DisplayName("description null → IN_PROGRESS")
        void nullDescription() {
            assertThat(CalendarTicketParser.detectStatus(null))
                    .isEqualTo(JiraStatusCode.IN_PROGRESS);
        }
    }

    // =========================================================================
    // detectPriority
    // =========================================================================

    @Nested
    @DisplayName("detectPriority")
    class DetectPriorityTest {

        @Test
        @DisplayName("Priority: High → HIGH")
        void high() {
            assertThat(CalendarTicketParser.detectPriority("Priority: High"))
                    .isEqualTo(JiraPriorityCode.HIGH);
        }

        @Test
        @DisplayName("Priority 라인 없음 → UNKNOWN")
        void noPriority() {
            assertThat(CalendarTicketParser.detectPriority("Status: Done"))
                    .isEqualTo(JiraPriorityCode.UNKNOWN);
        }

        @Test
        @DisplayName("description null → UNKNOWN")
        void nullDescription() {
            assertThat(CalendarTicketParser.detectPriority(null))
                    .isEqualTo(JiraPriorityCode.UNKNOWN);
        }
    }

    // =========================================================================
    // detectStartDate
    // =========================================================================

    @Nested
    @DisplayName("detectStartDate")
    class DetectStartDateTest {

        @Test
        @DisplayName("extendedProperties에 jiraStartDate → 해당 날짜")
        void fromExtendedProperties() {
            Event event = new Event();
            Event.ExtendedProperties ext = new Event.ExtendedProperties();
            ext.setPrivate(Map.of("jiraStartDate", "2026-04-01"));
            event.setExtendedProperties(ext);

            LocalDate result = CalendarTicketParser.detectStartDate(event);
            assertThat(result).isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        @DisplayName("description에 Start Date 라인 → 해당 날짜")
        void fromDescription() {
            Event event = new Event()
                    .setDescription("Status: In Progress\nStart Date: 2026-03-15");

            LocalDate result = CalendarTicketParser.detectStartDate(event);
            assertThat(result).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        @DisplayName("둘 다 없음 → null")
        void noStartDate() {
            Event event = new Event().setDescription("Status: Done");
            assertThat(CalendarTicketParser.detectStartDate(event)).isNull();
        }
    }

    // =========================================================================
    // dateOf / titleOf
    // =========================================================================

    @Nested
    @DisplayName("dateOf")
    class DateOfTest {

        @Test
        @DisplayName("종일 이벤트 → start.date에서 추출")
        void allDayEvent() {
            Event event = new Event().setStart(
                    new EventDateTime().setDate(
                            new com.google.api.client.util.DateTime("2026-04-12")));

            assertThat(CalendarTicketParser.dateOf(event))
                    .isEqualTo(LocalDate.of(2026, 4, 12));
        }

        @Test
        @DisplayName("start null → null")
        void nullStart() {
            Event event = new Event();
            assertThat(CalendarTicketParser.dateOf(event)).isNull();
        }
    }

    @Nested
    @DisplayName("titleOf")
    class TitleOfTest {

        @Test
        @DisplayName("summary 있으면 반환")
        void hasSummary() {
            Event event = new Event().setSummary("테스트 제목");
            assertThat(CalendarTicketParser.titleOf(event)).isEqualTo("테스트 제목");
        }

        @Test
        @DisplayName("summary null → 빈 문자열")
        void nullSummary() {
            Event event = new Event();
            assertThat(CalendarTicketParser.titleOf(event)).isEmpty();
        }
    }

    // =========================================================================
    // fetchStatusFromJira
    // =========================================================================

    @Nested
    @DisplayName("fetchStatusFromJira")
    class FetchStatusFromJiraTest {

        @Test
        @DisplayName("jiraClient null → 빈 맵")
        void nullClient() {
            Map<String, JiraStatusCode> result =
                    CalendarTicketParser.fetchStatusFromJira(Set.of("CCE-123"), null, "test");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("빈 이슈 키 셋 → 빈 맵")
        void emptyKeys() {
            Map<String, JiraStatusCode> result =
                    CalendarTicketParser.fetchStatusFromJira(Set.of(), jiraClient, "test");
            assertThat(result).isEmpty();
            verifyNoInteractions(jiraClient);
        }

        @Test
        @DisplayName("정상 배치 조회 → 상태 맵 반환")
        void normalBatch() throws Exception {
            ObjectNode root = OM.createObjectNode();
            ArrayNode issues = root.putArray("issues");
            ObjectNode issue = issues.addObject();
            issue.put("key", "CCE-123");
            issue.putObject("fields")
                    .putObject("status")
                    .putObject("statusCategory")
                    .put("key", "done");

            when(jiraClient.search(anyString(), anyString(), anyInt())).thenReturn(root);

            Map<String, JiraStatusCode> result =
                    CalendarTicketParser.fetchStatusFromJira(Set.of("CCE-123"), jiraClient, "test");

            assertThat(result).containsEntry("CCE-123", JiraStatusCode.DONE);
        }

        @Test
        @DisplayName("Jira 조회 실패 → 빈 맵 (예외 미전파)")
        void jiraFailure() throws Exception {
            when(jiraClient.search(anyString(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("connection error"));

            Map<String, JiraStatusCode> result =
                    CalendarTicketParser.fetchStatusFromJira(Set.of("CCE-123"), jiraClient, "test");

            assertThat(result).isEmpty();
        }
    }
}
