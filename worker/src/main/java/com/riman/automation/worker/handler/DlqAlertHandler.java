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
 * SQS DLQ 알림 Lambda 핸들러.
 * Worker/Groupware SQS 큐의 Dead Letter Queue에 메시지가 적재되면 트리거되어 모니터링 Slack 채널로 알림을 보낸다.
 * 필수 환경변수: SLACK_BOT_TOKEN, MONITORING_SLACK_CHANNEL.
 */
@Slf4j
public class DlqAlertHandler implements RequestHandler<SQSEvent, Void> {

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

  /**
   * DLQ 배치의 각 메시지를 Slack 알림으로 변환한다.
   * 개별 전송 실패는 로그와 Sentry로만 남기고 다른 메시지 처리는 계속한다.
   */
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

  /**
   * DLQ 메시지 메타데이터를 Slack 블록 메시지로 구성해 모니터링 채널로 전송한다.
   */
  private void sendAlert(SQSMessage message, Context context) {
    String messageType = extractMessageType(message);
    String sourceArn = message.getEventSourceArn() != null
        ? message.getEventSourceArn() : "unknown";
    String timestamp = DateTimeUtil.nowKst().toString();
    int bodyLength = message.getBody() != null ? message.getBody().length() : 0;

    String payload = SlackBlockBuilder.forChannel(monitoringChannel)
        .fallbackText("[DLQ] 처리 실패 메시지 감지: " + messageType)
        .header("⚠️ SQS DLQ 메시지 감지")
        .section(String.join("\n",
            "*메시지 타입:* `" + messageType + "`",
            "*메시지 ID:* `" + message.getMessageId() + "`",
            "*소스 큐:* `" + extractQueueName(sourceArn) + "`",
            "*본문 크기:* " + bodyLength + " bytes",
            "*감지 시각:* " + timestamp
        ))
        .context("CloudWatch Logs에서 messageId로 상세 내용을 확인하세요. requestId: "
            + context.getAwsRequestId())
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

  /**
   * ARN의 마지막 콜론 이후 토큰을 큐 이름으로 추출한다.
   */
  private static String extractQueueName(String arn) {
    if (arn == null || !arn.contains(":")) return arn;
    int lastColon = arn.lastIndexOf(':');
    return lastColon < arn.length() - 1 ? arn.substring(lastColon + 1) : arn;
  }
}
