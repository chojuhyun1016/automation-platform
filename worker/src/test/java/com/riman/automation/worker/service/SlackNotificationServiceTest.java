package com.riman.automation.worker.service;

import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SlackNotificationServiceTest {

    @Mock
    private SecretsManagerClient secretsManagerClient;

    @Mock
    private TeamMemberService teamMemberService;

    @Mock
    private SlackClient slackClient;

    private SlackNotificationService service;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        // spy로 buildSlackClient를 mock SlackClient 반환하도록 설정
        service = spy(new SlackNotificationService(secretsManagerClient, teamMemberService));
        doReturn(slackClient).when(service).buildSlackClient(anyString());

        // Secrets Manager 토큰 반환 설정
        GetSecretValueResponse tokenResponse = GetSecretValueResponse.builder()
                .secretString("{\"token\": \"xoxb-test-token\"}")
                .build();
        lenient().when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(tokenResponse);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // =========================================================================
    // 채널 / DM 분기
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — 채널/DM 분기")
    class ChannelDmBranchTest {

        @Test
        @DisplayName("sendToChannel=true면 채널에 메시지를 전송한다")
        void sendNotification_channelEnabled_sendsToChannel() {
            ProjectRouting routing = createRouting(true, false);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");

            service.sendNotification(event, routing);

            // 시간 헤더 + 본문 = 2회 postMessage
            verify(slackClient, times(2)).postMessage(anyString());
        }

        @Test
        @DisplayName("sendToIndividuals=true면 담당자에게 DM을 전송한다")
        void sendNotification_individualsEnabled_sendsDm() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-001");

            TeamMember member = createTeamMember("홍길동", "jira-001", "U001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            service.sendNotification(event, routing);

            // DM 1회
            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("sendToChannel=false, sendToIndividuals=false면 전송하지 않는다")
        void sendNotification_allDisabled_noSend() {
            ProjectRouting routing = createRouting(false, false);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");

            service.sendNotification(event, routing);

            verify(slackClient, never()).postMessage(anyString());
        }
    }

    // =========================================================================
    // DM 수신자 — 담당자 변경 4가지 시나리오
    // =========================================================================

    @Nested
    @DisplayName("DM 수신자 결정 — 담당자 변경")
    class AssigneeChangeTest {

        @Test
        @DisplayName("팀원→팀원: from + to 2명에게 DM")
        void assigneeChange_teamToTeam_dmBoth() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-002"); // current = to
            addAssigneeChangelog(event, "jira-001", "jira-002"); // from, to

            TeamMember fromMember = createTeamMember("홍길동", "jira-001", "U001");
            TeamMember toMember = createTeamMember("김철수", "jira-002", "U002");

            when(teamMemberService.findByAccountId("jira-001")).thenReturn(fromMember);
            when(teamMemberService.findByAccountId("jira-002")).thenReturn(toMember);
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(fromMember);
            when(teamMemberService.findBySlackUserId("U002")).thenReturn(toMember);

            service.sendNotification(event, routing);

            // 2명 DM = 2회 postMessage
            verify(slackClient, times(2)).postMessage(anyString());
        }

        @Test
        @DisplayName("팀원→비팀원: from(팀원) 1명에게만 DM")
        void assigneeChange_teamToNonTeam_dmFrom() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-ext-1"); // to = 비팀원
            addAssigneeChangelog(event, "jira-001", "jira-ext-1");

            TeamMember fromMember = createTeamMember("홍길동", "jira-001", "U001");

            when(teamMemberService.findByAccountId("jira-001")).thenReturn(fromMember);
            when(teamMemberService.findByAccountId("jira-ext-1")).thenReturn(null); // 비팀원
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(fromMember);

            service.sendNotification(event, routing);

            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("비팀원→팀원: to(팀원) 1명에게만 DM")
        void assigneeChange_nonTeamToTeam_dmTo() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-002"); // to = 팀원
            addAssigneeChangelog(event, "jira-ext-1", "jira-002");

            TeamMember toMember = createTeamMember("김철수", "jira-002", "U002");

            when(teamMemberService.findByAccountId("jira-ext-1")).thenReturn(null); // 비팀원
            when(teamMemberService.findByAccountId("jira-002")).thenReturn(toMember);
            when(teamMemberService.findBySlackUserId("U002")).thenReturn(toMember);

            service.sendNotification(event, routing);

            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("비팀원→비팀원: DM 없음")
        void assigneeChange_nonTeamToNonTeam_noDm() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-ext-2");
            addAssigneeChangelog(event, "jira-ext-1", "jira-ext-2");

            when(teamMemberService.findByAccountId("jira-ext-1")).thenReturn(null);
            when(teamMemberService.findByAccountId("jira-ext-2")).thenReturn(null);

            service.sendNotification(event, routing);

            verify(slackClient, never()).postMessage(anyString());
        }
    }

    // =========================================================================
    // DM 수신자 — 기타 변경 이벤트
    // =========================================================================

    @Nested
    @DisplayName("DM 수신자 결정 — 기타 변경")
    class OtherChangeTest {

        @Test
        @DisplayName("담당자가 팀원이면 1명에게 DM")
        void otherChange_teamAssignee_dmOne() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-001");
            // changelog에 assignee 없음 → 기타 변경

            TeamMember member = createTeamMember("홍길동", "jira-001", "U001");
            when(teamMemberService.findByAccountId("jira-001")).thenReturn(member);
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            service.sendNotification(event, routing);

            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("담당자가 비팀원이면 DM 없음")
        void otherChange_nonTeamAssignee_noDm() {
            ProjectRouting routing = createRouting(false, true);
            JiraWebhookEvent event = createBasicEvent("CCE-100", "jira:issue_updated");
            setAssignee(event, "jira-ext-1");

            when(teamMemberService.findByAccountId("jira-ext-1")).thenReturn(null);

            service.sendNotification(event, routing);

            verify(slackClient, never()).postMessage(anyString());
        }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private ProjectRouting createRouting(boolean sendToChannel, boolean sendToIndividuals) {
        ProjectRouting routing = new ProjectRouting();
        routing.setSlackChannelId("C-TEST");
        routing.setSlackBotTokenSecret("slack/bot-token");
        routing.setSendToChannel(sendToChannel);
        routing.setSendToIndividuals(sendToIndividuals);
        return routing;
    }

    private JiraWebhookEvent createBasicEvent(String issueKey, String webhookEvent) {
        JiraWebhookEvent event = new JiraWebhookEvent();
        event.setWebhookEvent(webhookEvent);

        JiraWebhookEvent.Issue issue = new JiraWebhookEvent.Issue();
        issue.setKey(issueKey);

        JiraWebhookEvent.Fields fields = new JiraWebhookEvent.Fields();
        fields.setSummary("테스트 이슈");

        JiraWebhookEvent.Status status = new JiraWebhookEvent.Status();
        status.setName("In Progress");
        fields.setStatus(status);

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
        assignee.setDisplayName("User " + accountId);
        event.getIssue().getFields().setAssignee(assignee);
    }

    private void addAssigneeChangelog(JiraWebhookEvent event, String fromId, String toId) {
        JiraWebhookEvent.ChangeItem changeItem = new JiraWebhookEvent.ChangeItem();
        changeItem.setField("assignee");
        changeItem.setFrom(fromId);
        changeItem.setTo(toId);

        JiraWebhookEvent.Changelog changelog = new JiraWebhookEvent.Changelog();
        changelog.setItems(List.of(changeItem));
        event.setChangelog(changelog);
    }

    private TeamMember createTeamMember(String name, String jiraAccountId, String slackUserId) {
        TeamMember member = new TeamMember();
        member.setName(name);
        member.setJiraAccountId(jiraAccountId);
        member.setSlackUserId(slackUserId);
        member.setActive(true);
        return member;
    }
}
