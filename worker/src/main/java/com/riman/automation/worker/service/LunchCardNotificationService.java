package com.riman.automation.worker.service;

import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 점심카드 신청/취소 Slack 알림 서비스.
 * 지정된 팀 채널에 점심카드 신청/취소 알림을 전송한다.
 * SLACK_BOT_TOKEN 환경변수가 비어있으면 비활성 상태로 동작한다.
 */
@Slf4j
public class LunchCardNotificationService {

  private final SlackClient slackClient;
  private final String notificationChannelId;
  private final boolean enabled;

  /**
   * 운영 생성자. ConfigService에서 채널 ID를 받아 초기화한다.
   */
  public LunchCardNotificationService(String notificationChannelId) {
    String botToken = System.getenv("SLACK_BOT_TOKEN");

    if (notificationChannelId == null || notificationChannelId.isBlank()
        || botToken == null || botToken.isBlank()) {
      log.info("[LunchCardNotification] 비활성 (channel={}, botToken={})",
          notificationChannelId != null ? "설정됨" : "null",
          botToken != null ? "설정됨" : "null");
      this.slackClient = null;
      this.notificationChannelId = null;
      this.enabled = false;
      return;
    }

    this.slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
    this.notificationChannelId = notificationChannelId;
    this.enabled = true;
    log.info("[LunchCardNotification] 활성: channel={}", notificationChannelId);
  }

  /**
   * 테스트용 생성자. SlackClient를 명시적으로 주입한다.
   */
  LunchCardNotificationService(SlackClient slackClient, String notificationChannelId) {
    this.slackClient = slackClient;
    this.notificationChannelId = notificationChannelId;
    this.enabled = slackClient != null && notificationChannelId != null;
  }

  /**
   * 점심카드 신청/취소 알림을 팀 채널에 전송한다.
   *
   * @param name   사용자 이름
   * @param action "apply" 또는 "cancel"
   * @param date   대상 날짜 (yyyy-MM-dd)
   */
  public void sendNotification(String name, String action, String date) {
    if (!enabled) {
      log.debug("[LunchCardNotification] 비활성 상태 → 알림 생략: name={}, action={}", name, action);
      return;
    }

    try {
      String actionLabel = "apply".equals(action) ? "신청" : "취소";
      String emoji = "apply".equals(action) ? "\uD83C\uDF7D\uFE0F" : "❌";
      String timestamp = DateTimeUtil.nowKst().toString();

      String payload = SlackBlockBuilder.forChannel(notificationChannelId)
          .fallbackText("[점심카드] " + name + " " + actionLabel)
          .header(emoji + " 점심카드 " + actionLabel)
          .section(String.join("\n",
              "*이름:* " + name,
              "*날짜:* " + date,
              "*처리:* " + actionLabel
          ))
          .context("처리 시각: " + timestamp)
          .build();

      slackClient.postMessage(payload);
      log.info("[LunchCardNotification] 알림 전송 완료: name={}, action={}, date={}", name, action, date);
    } catch (Exception e) {
      log.error("[LunchCardNotification] 알림 전송 실패: name={}, action={}", name, action, e);
      throw e;
    }
  }
}
