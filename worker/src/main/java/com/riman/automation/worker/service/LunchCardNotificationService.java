package com.riman.automation.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.time.Instant;
import java.util.Map;

/**
 * 점심카드 신청/취소 Slack 알림 서비스.
 * 지정된 팀 채널에 점심카드 신청/취소 알림을 전송한다.
 * Bot 토큰은 Secrets Manager에서 조회하며 5분 TTL 캐시를 적용한다.
 */
@Slf4j
public class LunchCardNotificationService {

  private static final long TOKEN_CACHE_TTL_SECONDS = 300;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final SecretsManagerClient secretsManagerClient;
  private final String notificationChannelId;
  private final String secretName;
  private final boolean enabled;

  /** 토큰 TTL 캐시. */
  private CachedToken cachedToken;

  /**
   * 운영 생성자. ConfigService에서 채널 ID와 Secrets Manager 시크릿명을 받아 초기화한다.
   */
  public LunchCardNotificationService(String notificationChannelId, String secretName) {
    if (notificationChannelId == null || notificationChannelId.isBlank()
        || secretName == null || secretName.isBlank()) {
      log.info("[LunchCardNotification] 비활성 (channel={}, secretName={})",
          notificationChannelId != null ? "설정됨" : "null",
          secretName != null ? "설정됨" : "null");
      this.secretsManagerClient = null;
      this.notificationChannelId = null;
      this.secretName = null;
      this.enabled = false;
      return;
    }

    this.secretsManagerClient = SecretsManagerClient.builder().build();
    this.notificationChannelId = notificationChannelId;
    this.secretName = secretName;
    this.enabled = true;
    log.info("[LunchCardNotification] 활성: channel={}, secretName={}", notificationChannelId, secretName);
  }

  /**
   * 테스트용 생성자. SecretsManagerClient를 명시적으로 주입한다.
   */
  LunchCardNotificationService(SecretsManagerClient secretsManagerClient,
                               String notificationChannelId, String secretName) {
    this.secretsManagerClient = secretsManagerClient;
    this.notificationChannelId = notificationChannelId;
    this.secretName = secretName;
    this.enabled = secretsManagerClient != null
        && notificationChannelId != null && secretName != null;
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
      String token = getBotToken();
      SlackClient slackClient = buildSlackClient(token);

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

  /**
   * Secrets Manager에서 Bot 토큰을 조회한다. 5분 TTL 캐시를 적용하여 반복 조회를 최소화한다.
   */
  private String getBotToken() {
    if (cachedToken != null && !cachedToken.isExpired()) return cachedToken.token;

    try {
      GetSecretValueResponse response = secretsManagerClient.getSecretValue(
          GetSecretValueRequest.builder().secretId(secretName).build());

      @SuppressWarnings("unchecked")
      Map<String, String> secret = objectMapper.readValue(
          response.secretString(), Map.class);
      String token = secret.get("token");
      cachedToken = new CachedToken(token, Instant.now().plusSeconds(TOKEN_CACHE_TTL_SECONDS));
      log.info("[LunchCardNotification] Bot token 캐시: secretName={}", secretName);
      return token;

    } catch (Exception e) {
      log.error("[LunchCardNotification] Bot token 조회 실패: secretName={}", secretName, e);
      throw new ExternalApiClientException("SecretsManager",
          "Bot token 조회 실패: secretName=" + secretName, e);
    }
  }

  /**
   * 토큰 문자열을 TokenProvider 람다로 감싸 SlackClient를 생성한다.
   * package-private: 테스트에서 spy로 override 가능하도록 의도적으로 열어둠.
   */
  SlackClient buildSlackClient(String token) {
    TokenProvider tokenProvider = () -> token;
    return new SlackClient(tokenProvider);
  }

  /**
   * Bot 토큰 TTL 캐시 항목.
   */
  private static class CachedToken {
    final String token;
    final Instant expiresAt;

    CachedToken(String token, Instant expiresAt) {
      this.token = token;
      this.expiresAt = expiresAt;
    }

    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }
}
