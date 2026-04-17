package com.riman.automation.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.ExternalApiClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LunchCardNotificationServiceTest {

    private static final String CHANNEL_ID = "C-LUNCH-TEST";
    private static final String SECRET_NAME = "automation-slack-bot-token";
    private static final String BOT_TOKEN = "xoxb-test-token-12345";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SecretsManagerClient secretsManagerClient;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);

        // 기본 Secrets Manager mock 설정
        String secretJson = objectMapper.writeValueAsString(Map.of("token", BOT_TOKEN));
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString(secretJson).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // =========================================================================
    // apply 시 Secrets Manager 조회 + postMessage 호출 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — apply (신청)")
    class ApplyTest {

        @Test
        @DisplayName("apply 시 Secrets Manager를 조회하고 알림을 전송한다")
        void apply_queriesSecretsManagerAndSendsNotification() {
            var service = spy(new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME));
            var mockClient = mock(com.riman.automation.clients.slack.SlackClient.class);
            doReturn(mockClient).when(service).buildSlackClient(anyString());

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(secretsManagerClient).getSecretValue(any(GetSecretValueRequest.class));
            verify(mockClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("apply 메시지에 '신청' 라벨과 사용자 정보가 포함된다")
        void apply_messageContainsApplyLabel() {
            var service = spy(new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME));
            var mockClient = mock(com.riman.automation.clients.slack.SlackClient.class);
            doReturn(mockClient).when(service).buildSlackClient(anyString());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(mockClient).postMessage(captor.capture());
            String payload = captor.getValue();
            assertThat(payload).contains("신청");
            assertThat(payload).contains("홍길동");
            assertThat(payload).contains("2024-06-10");
        }
    }

    // =========================================================================
    // cancel 시 메시지 포맷 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — cancel (취소)")
    class CancelTest {

        @Test
        @DisplayName("cancel 메시지에 '취소' 라벨과 사용자 정보가 포함된다")
        void cancel_messageContainsCancelLabel() {
            var service = spy(new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME));
            var mockClient = mock(com.riman.automation.clients.slack.SlackClient.class);
            doReturn(mockClient).when(service).buildSlackClient(anyString());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            service.sendNotification("김철수", "cancel", "2024-06-11");

            verify(mockClient).postMessage(captor.capture());
            String payload = captor.getValue();
            assertThat(payload).contains("취소");
            assertThat(payload).contains("김철수");
            assertThat(payload).contains("2024-06-11");
        }
    }

    // =========================================================================
    // disabled 상태 (비활성) 무동작 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — disabled 상태")
    class DisabledTest {

        @Test
        @DisplayName("secretsManagerClient가 null이면 알림을 전송하지 않는다")
        void disabled_nullClient_noCall() {
            var service = new LunchCardNotificationService(null, CHANNEL_ID, SECRET_NAME);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
        }

        @Test
        @DisplayName("channelId가 null이면 알림을 전송하지 않는다")
        void disabled_nullChannel_noCall() {
            var service = new LunchCardNotificationService(secretsManagerClient, null, SECRET_NAME);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
        }

        @Test
        @DisplayName("secretName이 null이면 알림을 전송하지 않는다")
        void disabled_nullSecretName_noCall() {
            var service = new LunchCardNotificationService(secretsManagerClient, CHANNEL_ID, null);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(secretsManagerClient, never()).getSecretValue(any(GetSecretValueRequest.class));
        }
    }

    // =========================================================================
    // 예외 전파 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — 예외 처리")
    class ExceptionTest {

        @Test
        @DisplayName("Secrets Manager 조회 실패 시 ExternalApiClientException 전파")
        void secretsManagerFails_exceptionPropagated() {
            when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                    .thenThrow(new RuntimeException("SecretsManager error"));

            var service = new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME);

            assertThatThrownBy(() ->
                    service.sendNotification("홍길동", "apply", "2024-06-10"))
                    .isInstanceOf(ExternalApiClientException.class)
                    .hasMessageContaining("Bot token 조회 실패");
        }

        @Test
        @DisplayName("postMessage 실패 시 예외가 그대로 전파된다")
        void postMessageFails_exceptionPropagated() {
            var service = spy(new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME));
            var mockClient = mock(com.riman.automation.clients.slack.SlackClient.class);
            doReturn(mockClient).when(service).buildSlackClient(anyString());
            doThrow(new RuntimeException("Slack API error"))
                    .when(mockClient).postMessage(anyString());

            assertThatThrownBy(() ->
                    service.sendNotification("홍길동", "apply", "2024-06-10"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Slack API error");
        }
    }

    // =========================================================================
    // 토큰 캐시 검증
    // =========================================================================

    @Nested
    @DisplayName("getBotToken — 캐시")
    class TokenCacheTest {

        @Test
        @DisplayName("연속 호출 시 Secrets Manager는 1번만 조회된다 (캐시)")
        void consecutiveCalls_secretsManagerCalledOnce() {
            var service = spy(new LunchCardNotificationService(
                    secretsManagerClient, CHANNEL_ID, SECRET_NAME));
            var mockClient = mock(com.riman.automation.clients.slack.SlackClient.class);
            doReturn(mockClient).when(service).buildSlackClient(anyString());

            service.sendNotification("홍길동", "apply", "2024-06-10");
            service.sendNotification("김철수", "cancel", "2024-06-11");

            verify(secretsManagerClient, times(1)).getSecretValue(any(GetSecretValueRequest.class));
        }
    }
}
