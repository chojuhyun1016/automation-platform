package com.riman.automation.worker.service;

import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import com.riman.automation.worker.service.JiraCalendarMappingService.MappingEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private GoogleCalendarClient calendarClient;

    @Mock
    private TeamMemberService teamMemberService;

    @Mock
    private JiraCalendarMappingService mappingService;

    private CalendarService calendarService;

    @BeforeEach
    void setUp() {
        calendarService = new CalendarService(calendarClient, teamMemberService, mappingService);
    }

    // =========================================================================
    // processJiraEvent — CREATE
    // =========================================================================

    @Nested
    @DisplayName("processJiraEvent — CREATE")
    class CreateTest {

        @Test
        @DisplayName("duedate 있고 팀원이면 캘린더 이벤트를 생성한다")
        void create_withDuedate_teamMember_createsEvent() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_created", "2024-06-15", null);
            setAssignee(event, "jira-001");
            ProjectRouting routing = createRouting("cal-1");

            TeamMember member = createTeamMember("홍길동", "jira-001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(null);

            Event createdEvent = new Event().setId("evt-new");
            when(calendarClient.insertEvent(eq("cal-1"), any(Event.class))).thenReturn(createdEvent);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient).insertEvent(eq("cal-1"), any(Event.class));
            verify(mappingService).saveMapping("CCE-100", "cal-1", "evt-new", "홍길동");
        }

        @Test
        @DisplayName("duedate 없으면 CREATE를 스킵한다")
        void create_noDuedate_skips() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_created", null, null);
            ProjectRouting routing = createRouting("cal-1");

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient, never()).insertEvent(anyString(), any(Event.class));
        }

        @Test
        @DisplayName("비팀원 담당이면 CREATE를 스킵한다")
        void create_nonTeamMember_skips() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_created", "2024-06-15", null);
            setAssignee(event, "jira-ext-1");
            ProjectRouting routing = createRouting("cal-1");

            when(teamMemberService.findByAccountId("jira-ext-1")).thenReturn(null);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient, never()).insertEvent(anyString(), any(Event.class));
        }

        @Test
        @DisplayName("이미 존재하는 이벤트면 UPDATE로 전환한다 (중복 생성 방지)")
        void create_existingEvent_switchesToUpdate() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_created", "2024-06-15", null);
            setAssignee(event, "jira-001");
            ProjectRouting routing = createRouting("cal-1");

            TeamMember member = createTeamMember("홍길동", "jira-001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);

            MappingEntry existing = new MappingEntry("CCE-100", "cal-1", "evt-existing", "홍길동");
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(existing);

            Event updatedEvent = new Event().setId("evt-existing");
            when(calendarClient.updateEvent(eq("cal-1"), eq("evt-existing"), any(Event.class)))
                    .thenReturn(updatedEvent);

            calendarService.processJiraEvent(event, routing);

            // INSERT가 아닌 UPDATE가 호출되어야 한다
            verify(calendarClient, never()).insertEvent(anyString(), any(Event.class));
            verify(calendarClient).updateEvent(eq("cal-1"), eq("evt-existing"), any(Event.class));
        }

        @Test
        @DisplayName("이벤트 제목이 [Jira] CCE-100 (홍길동) 형식이다")
        void create_eventSummaryFormat() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_created", "2024-06-15", null);
            setAssignee(event, "jira-001");
            ProjectRouting routing = createRouting("cal-1");

            TeamMember member = createTeamMember("홍길동", "jira-001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(null);

            Event createdEvent = new Event().setId("evt-new");
            when(calendarClient.insertEvent(eq("cal-1"), any(Event.class))).thenReturn(createdEvent);

            calendarService.processJiraEvent(event, routing);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(calendarClient).insertEvent(eq("cal-1"), captor.capture());

            assertThat(captor.getValue().getSummary()).isEqualTo("[Jira] CCE-100 (홍길동)");
        }
    }

    // =========================================================================
    // processJiraEvent — UPDATE
    // =========================================================================

    @Nested
    @DisplayName("processJiraEvent — UPDATE")
    class UpdateTest {

        @Test
        @DisplayName("기존 이벤트가 있으면 캘린더를 업데이트한다")
        void update_existingEvent_updatesCalendar() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_updated", "2024-06-20", null);
            setAssignee(event, "jira-001");
            ProjectRouting routing = createRouting("cal-1");

            TeamMember member = createTeamMember("홍길동", "jira-001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);

            MappingEntry existing = new MappingEntry("CCE-100", "cal-1", "evt-1", "홍길동");
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(existing);

            Event updatedEvent = new Event().setId("evt-1");
            when(calendarClient.updateEvent(eq("cal-1"), eq("evt-1"), any(Event.class)))
                    .thenReturn(updatedEvent);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient).updateEvent(eq("cal-1"), eq("evt-1"), any(Event.class));
            verify(mappingService).saveMapping("CCE-100", "cal-1", "evt-1", "홍길동");
        }

        @Test
        @DisplayName("기존 이벤트 없으면 CREATE로 전환한다")
        void update_noExistingEvent_switchesToCreate() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_updated", "2024-06-20", null);
            setAssignee(event, "jira-001");
            ProjectRouting routing = createRouting("cal-1");

            TeamMember member = createTeamMember("홍길동", "jira-001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(null);
            // findJiraEventByIssueKey fallback도 null (빈 리스트)
            when(calendarClient.listEvents(eq("cal-1"), anyString(), anyString(), anyString()))
                    .thenReturn(List.of());

            Event createdEvent = new Event().setId("evt-new");
            when(calendarClient.insertEvent(eq("cal-1"), any(Event.class))).thenReturn(createdEvent);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient).insertEvent(eq("cal-1"), any(Event.class));
        }

        @Test
        @DisplayName("duedate 제거 시 DELETE로 전환한다")
        void update_duedateRemoved_deletesEvent() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_updated", null, null);
            ProjectRouting routing = createRouting("cal-1");

            MappingEntry existing = new MappingEntry("CCE-100", "cal-1", "evt-1", "홍길동");
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(existing);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient).deleteEvent("cal-1", "evt-1");
            verify(mappingService).deleteMapping("CCE-100", "cal-1");
        }
    }

    // =========================================================================
    // processJiraEvent — DELETE
    // =========================================================================

    @Nested
    @DisplayName("processJiraEvent — DELETE")
    class DeleteTest {

        @Test
        @DisplayName("기존 이벤트가 있으면 삭제한다")
        void delete_existingEvent_deletesCalendarAndMapping() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_deleted", null, null);
            ProjectRouting routing = createRouting("cal-1");

            MappingEntry existing = new MappingEntry("CCE-100", "cal-1", "evt-1", "홍길동");
            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(existing);

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient).deleteEvent("cal-1", "evt-1");
            verify(mappingService).deleteMapping("CCE-100", "cal-1");
        }

        @Test
        @DisplayName("기존 이벤트 없으면 아무것도 하지 않는다")
        void delete_noExistingEvent_doesNothing() {
            JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_deleted", null, null);
            ProjectRouting routing = createRouting("cal-1");

            when(mappingService.findMapping("CCE-100", "cal-1")).thenReturn(null);
            when(calendarClient.listEvents(eq("cal-1"), anyString(), anyString(), anyString()))
                    .thenReturn(List.of());

            calendarService.processJiraEvent(event, routing);

            verify(calendarClient, never()).deleteEvent(anyString(), anyString());
        }
    }

    // =========================================================================
    // processJiraEvent — 알 수 없는 이벤트
    // =========================================================================

    @Test
    @DisplayName("알 수 없는 webhookEvent면 아무것도 하지 않는다")
    void processJiraEvent_unknownEvent_doesNothing() {
        JiraWebhookEvent event = buildEvent("CCE-100", "jira:issue_commented", null, null);
        ProjectRouting routing = createRouting("cal-1");

        calendarService.processJiraEvent(event, routing);

        verifyNoInteractions(calendarClient);
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private JiraWebhookEvent buildEvent(String issueKey, String webhookEvent,
                                         String duedate, String startdate) {
        JiraWebhookEvent event = new JiraWebhookEvent();
        event.setWebhookEvent(webhookEvent);

        JiraWebhookEvent.Issue issue = new JiraWebhookEvent.Issue();
        issue.setKey(issueKey);

        JiraWebhookEvent.Fields fields = new JiraWebhookEvent.Fields();
        fields.setSummary("테스트 이슈");
        fields.setDuedate(duedate);
        fields.setStartdate(startdate);

        JiraWebhookEvent.Project project = new JiraWebhookEvent.Project();
        project.setKey("CCE");
        project.setName("CCE Project");
        fields.setProject(project);

        issue.setFields(fields);
        event.setIssue(issue);
        return event;
    }

    private void setAssignee(JiraWebhookEvent event, String accountId) {
        JiraWebhookEvent.User assignee = new JiraWebhookEvent.User();
        assignee.setAccountId(accountId);
        event.getIssue().getFields().setAssignee(assignee);
    }

    private ProjectRouting createRouting(String calendarId) {
        ProjectRouting routing = new ProjectRouting();
        routing.setCalendarId(calendarId);
        routing.setCalendarEnabled(true);
        return routing;
    }

    private TeamMember createTeamMember(String name, String jiraAccountId) {
        TeamMember member = new TeamMember();
        member.setName(name);
        member.setJiraAccountId(jiraAccountId);
        member.setActive(true);
        return member;
    }
}
