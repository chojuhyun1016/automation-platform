package com.riman.automation.worker.service;

import com.riman.automation.clients.slack.SlackClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LunchCardNotificationServiceTest {

    private static final String CHANNEL_ID = "C-LUNCH-TEST";

    @Mock
    private SlackClient slackClient;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // =========================================================================
    // apply 시 postMessage 호출 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — apply (신청)")
    class ApplyTest {

        @Test
        @DisplayName("apply 시 SlackClient.postMessage()를 호출한다")
        void apply_callsPostMessage() {
            var service = new LunchCardNotificationService(slackClient, CHANNEL_ID);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("apply 메시지에 '신청' 라벨과 사용자 정보가 포함된다")
        void apply_messageContainsApplyLabel() {
            var service = new LunchCardNotificationService(slackClient, CHANNEL_ID);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(slackClient).postMessage(captor.capture());
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
        @DisplayName("cancel 시 SlackClient.postMessage()를 호출한다")
        void cancel_callsPostMessage() {
            var service = new LunchCardNotificationService(slackClient, CHANNEL_ID);

            service.sendNotification("김철수", "cancel", "2024-06-11");

            verify(slackClient, times(1)).postMessage(anyString());
        }

        @Test
        @DisplayName("cancel 메시지에 '취소' 라벨과 사용자 정보가 포함된다")
        void cancel_messageContainsCancelLabel() {
            var service = new LunchCardNotificationService(slackClient, CHANNEL_ID);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            service.sendNotification("김철수", "cancel", "2024-06-11");

            verify(slackClient).postMessage(captor.capture());
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
        @DisplayName("slackClient가 null이면 postMessage를 호출하지 않는다")
        void disabled_nullClient_noCall() {
            var service = new LunchCardNotificationService(null, CHANNEL_ID);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(slackClient, never()).postMessage(anyString());
        }

        @Test
        @DisplayName("channelId가 null이면 postMessage를 호출하지 않는다")
        void disabled_nullChannel_noCall() {
            var service = new LunchCardNotificationService(slackClient, null);

            service.sendNotification("홍길동", "apply", "2024-06-10");

            verify(slackClient, never()).postMessage(anyString());
        }
    }

    // =========================================================================
    // 예외 전파 검증
    // =========================================================================

    @Nested
    @DisplayName("sendNotification — 예외 처리")
    class ExceptionTest {

        @Test
        @DisplayName("postMessage 실패 시 예외가 그대로 전파된다")
        void postMessageFails_exceptionPropagated() {
            var service = new LunchCardNotificationService(slackClient, CHANNEL_ID);
            doThrow(new RuntimeException("Slack API error"))
                    .when(slackClient).postMessage(anyString());

            assertThatThrownBy(() ->
                    service.sendNotification("홍길동", "apply", "2024-06-10"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Slack API error");
        }
    }
}
