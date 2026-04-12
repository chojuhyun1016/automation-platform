package com.riman.automation.groupware.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.groupware.dto.GroupwareAbsenceMessage;
import com.riman.automation.groupware.service.EcsTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupwareAbsenceFacadeTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Mock
    private EcsTaskService ecsTaskService;
    @Mock
    private SlackClient slackClient;
    @Mock
    private S3Client s3Client;

    private GroupwareAbsenceFacade facade;

    @BeforeEach
    void setUp() {
        facade = new GroupwareAbsenceFacade(
                ecsTaskService, slackClient, s3Client,
                "test-bucket", "groupware-config.json",
                "gw-credentials-secret", "slack-token-secret");
    }

    // =========================================================================
    // cancel 분기
    // =========================================================================

    @Nested
    @DisplayName("cancel 분기")
    class CancelTest {

        @Test
        @DisplayName("cancel 액션 → Slack DM 안내, ECS 미실행")
        void cancelAction() {
            GroupwareAbsenceMessage msg = createMessage("cancel");

            when(slackClient.openDm(anyString())).thenReturn("D12345");

            facade.handle(msg);

            verify(slackClient).openDm("U0627755JP7");
            verify(slackClient).postMessage(anyString());
            verifyNoInteractions(ecsTaskService);
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("cancel DM 실패 → 예외 무시, 정상 반환")
        void cancelDmFailure() {
            GroupwareAbsenceMessage msg = createMessage("cancel");

            when(slackClient.openDm(anyString())).thenThrow(new RuntimeException("Slack error"));

            facade.handle(msg); // 예외 없이 정상 반환
            verifyNoInteractions(ecsTaskService);
        }
    }

    // =========================================================================
    // apply 분기
    // =========================================================================

    @Nested
    @DisplayName("apply 분기")
    class ApplyTest {

        @Test
        @DisplayName("정상 apply → 결재자 resolve + ECS 실행 + 처리중 DM")
        void normalApply() {
            GroupwareAbsenceMessage msg = createMessage("apply");
            mockS3Config(createConfigJson(true, "조주현", "조주현"));

            when(ecsTaskService.runAbsenceTask(any())).thenReturn("arn:aws:ecs:task/123");
            when(slackClient.openDm(anyString())).thenReturn("D12345");

            facade.handle(msg);

            verify(ecsTaskService).runAbsenceTask(any());
            verify(slackClient, atLeastOnce()).openDm("U0627755JP7");
            verify(slackClient, atLeastOnce()).postMessage(anyString());
        }

        @Test
        @DisplayName("enabled=false → ECS 미실행")
        void disabled() {
            GroupwareAbsenceMessage msg = createMessage("apply");
            mockS3Config(createConfigJson(false, "", ""));

            facade.handle(msg);

            verifyNoInteractions(ecsTaskService);
        }

        @Test
        @DisplayName("ECS 실행 실패 → RuntimeException throw + 실패 DM")
        void ecsFailure() {
            GroupwareAbsenceMessage msg = createMessage("apply");
            mockS3Config(createConfigJson(true, "조주현", "조주현"));

            when(ecsTaskService.runAbsenceTask(any()))
                    .thenThrow(new RuntimeException("RunTask failed"));
            when(slackClient.openDm(anyString())).thenReturn("D12345");

            assertThatThrownBy(() -> facade.handle(msg))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("ECS Task 실행 실패");

            verify(slackClient, atLeastOnce()).postMessage(anyString());
        }

        @Test
        @DisplayName("S3 config 로드 실패 → ConfigException")
        void configLoadFailure() {
            GroupwareAbsenceMessage msg = createMessage("apply");

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(new RuntimeException("NoSuchKey"));

            assertThatThrownBy(() -> facade.handle(msg))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("groupware-config.json 로드 실패");
        }
    }

    // =========================================================================
    // 결재자 resolve
    // =========================================================================

    @Nested
    @DisplayName("결재자 resolve")
    class ApproverResolveTest {

        @Test
        @DisplayName("approval_rules 미설정 팀/역할 → 빈 문자열, ECS 정상 실행")
        void noApprovalRule() {
            GroupwareAbsenceMessage msg = createMessage("apply");
            // 다른 팀의 규칙만 있는 config
            String config = """
                    {
                      "groupware": { "enabled": true, "login_url": "https://gw.riman.com", "base_url": "https://gw.riman.com", "screenshot_bucket": "bucket", "screenshot_prefix": "prefix/" },
                      "approval_rules": {
                        "OTHER_TEAM": {
                          "Engineer": { "approver_name": "김영수", "approver_search_keyword": "김영수" }
                        }
                      }
                    }
                    """;
            mockS3Config(config);

            when(ecsTaskService.runAbsenceTask(any())).thenReturn("arn:task/123");
            when(slackClient.openDm(anyString())).thenReturn("D12345");

            facade.handle(msg);

            verify(ecsTaskService).runAbsenceTask(argThat(env ->
                    "".equals(env.get("APPROVER_NAME"))));
        }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private GroupwareAbsenceMessage createMessage(String action) {
        try {
            String json = String.format("""
                    {
                      "messageType": "GROUPWARE_ABSENCE",
                      "eventId": "evt-001",
                      "receivedAt": "2026-04-12T10:00:00",
                      "slackUserId": "U0627755JP7",
                      "memberName": "조주현",
                      "team": "CCE",
                      "role": "Engineer",
                      "absenceType": "연차",
                      "action": "%s",
                      "startDate": "2026-04-14",
                      "endDate": "2026-04-14",
                      "reason": "개인사유"
                    }
                    """, action);
            return OM.readValue(json, GroupwareAbsenceMessage.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createConfigJson(boolean enabled, String approverName, String approverKeyword) {
        return String.format("""
                {
                  "groupware": {
                    "enabled": %s,
                    "login_url": "https://gw.riman.com/login",
                    "base_url": "https://gw.riman.com",
                    "timeout_seconds": "120",
                    "screenshot_bucket": "test-screenshot-bucket",
                    "screenshot_prefix": "groupware-screenshots/"
                  },
                  "approval_rules": {
                    "CCE": {
                      "Engineer": {
                        "approver_name": "%s",
                        "approver_search_keyword": "%s"
                      }
                    }
                  }
                }
                """, enabled, approverName, approverKeyword);
    }

    private void mockS3Config(String configJson) {
        byte[] bytes = configJson.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(bytes));

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);
    }
}
