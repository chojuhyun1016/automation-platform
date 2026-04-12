package com.riman.automation.worker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.common.util.SentryInitializer;
import lombok.extern.slf4j.Slf4j;

/**
 * SQS DLQ 알림 Lambda Handler.
 *
 * <p>Worker/Groupware SQS 큐의 Dead Letter Queue에 메시지가 적재되면
 * 이 Lambda가 트리거되어 모니터링 Slack 채널에 알림을 전송한다.
 *
 * <p><b>환경변수:</b>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} — Slack Bot 토큰 (필수)</li>
 *   <li>{@code MONITORING_SLACK_CHANNEL} — 알림 대상 Slack 채널 ID (필수)</li>
 * </ul>
 */
@Slf4j
public class DlqAlertHandler implements RequestHandler<SQSEvent, Void> {

    private static final int BODY_PREVIEW_MAX_LENGTH = 500;

    private static final SlackClient slackClient;
    private static final String monitoringChannel;

    static {
        SentryInitializer.init("dlq-alert");

        String channel = System.getenv("MONITORING_SLACK_CHANNEL");
        if (channel == null || channel.isBlank()) {
            throw new ConfigException("필수 환경변수 미설정: MONITORING_SLACK_CHANNEL");
        }
        monitoringChannel = channel;

        slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
        log.info("[DlqAlertHandler] 초기화 완료: channel={}", monitoringChannel);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("[DlqAlertHandler] DLQ 메시지 수신: count={}, requestId={}",
                event.getRecords().size(), context.getAwsRequestId());

        for (SQSMessage message : event.getRecords()) {
            try {
                sendAlert(message, context);
            } catch (Exception e) {
                log.error("[DlqAlertHandler] 알림 전송 실패: messageId={}", message.getMessageId(), e);
                SentryInitializer.captureException(e, "sendDlqAlert");
            }
        }

        SentryInitializer.flush();
        return null;
    }

    private void sendAlert(SQSMessage message, Context context) {
        String messageType = extractMessageType(message);
        String bodyPreview = truncate(message.getBody(), BODY_PREVIEW_MAX_LENGTH);
        String sourceArn = message.getEventSourceArn() != null
                ? message.getEventSourceArn() : "unknown";
        String timestamp = DateTimeUtil.nowKst().toString();

        String payload = SlackBlockBuilder.forChannel(monitoringChannel)
                .fallbackText("[DLQ] 처리 실패 메시지 감지: " + messageType)
                .header("⚠️ SQS DLQ 메시지 감지")
                .section(String.join("\n",
                        "*메시지 타입:* `" + messageType + "`",
                        "*메시지 ID:* `" + message.getMessageId() + "`",
                        "*소스 큐:* `" + extractQueueName(sourceArn) + "`",
                        "*감지 시각:* " + timestamp
                ))
                .divider()
                .section("*메시지 본문 (미리보기):*\n```" + bodyPreview + "```")
                .context("Lambda requestId: " + context.getAwsRequestId())
                .build();

        slackClient.postMessage(payload);
        log.info("[DlqAlertHandler] 알림 전송 완료: messageId={}, type={}",
                message.getMessageId(), messageType);
    }

    private String extractMessageType(SQSMessage message) {
        if (message.getMessageAttributes() != null) {
            var attr = message.getMessageAttributes().get("messageType");
            if (attr != null && attr.getStringValue() != null) {
                return attr.getStringValue();
            }
        }
        return "unknown";
    }

    private static String extractQueueName(String arn) {
        if (arn == null || !arn.contains(":")) return arn;
        int lastColon = arn.lastIndexOf(':');
        return lastColon < arn.length() - 1 ? arn.substring(lastColon + 1) : arn;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "(empty)";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
