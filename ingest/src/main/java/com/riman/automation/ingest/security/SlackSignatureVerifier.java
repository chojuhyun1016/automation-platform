package com.riman.automation.ingest.security;

import com.riman.automation.common.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Slack 요청의 HMAC-SHA256 서명을 검증한다.
 * 검증 절차: 타임스탬프/서명 헤더 존재 확인, 5분 이내 여부 검사, 서명 재계산 후 비교.
 * 환경변수 {@code SLACK_SIGNING_SECRET} 미설정 시 인스턴스 생성이 실패한다.
 */
@Slf4j
public class SlackSignatureVerifier {

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final long MAX_AGE_SECONDS = 300;

  private final String signingSecret;

  public SlackSignatureVerifier() {
    this.signingSecret = System.getenv("SLACK_SIGNING_SECRET");
    if (signingSecret == null || signingSecret.isEmpty()) {
      throw new ConfigException("환경변수 미설정: SLACK_SIGNING_SECRET");
    }
  }

  /**
   * 요청 헤더와 body 기반으로 서명을 검증한다.
   * 검증 실패 원인은 모두 WARN 레벨로 로깅되며 false 를 반환한다.
   */
  public boolean verify(Map<String, String> headers, String body) {
    try {
      String timestamp = getHeader(headers, "X-Slack-Request-Timestamp");
      String signature = getHeader(headers, "X-Slack-Signature");

      if (timestamp == null || signature == null) {
        log.warn("Slack 서명 헤더 없음");
        return false;
      }

      // Replay 공격 방지를 위해 5분 이상 지난 요청은 거부한다.
      long requestTime = Long.parseLong(timestamp);
      long now = System.currentTimeMillis() / 1000;
      if (Math.abs(now - requestTime) > MAX_AGE_SECONDS) {
        log.warn("타임스탬프 만료: requestTime={}, now={}", requestTime, now);
        return false;
      }

      // Slack 문서 규격: "v0:{timestamp}:{body}" 를 signing secret 으로 HMAC-SHA256 서명한다.
      String baseString = "v0:" + timestamp + ":" + body;
      String computed = "v0=" + hmacSha256(signingSecret, baseString);

      boolean valid = computed.equals(signature);
      if (!valid) {
        log.warn("서명 불일치");
      }
      return valid;

    } catch (Exception e) {
      log.error("서명 검증 중 오류", e);
      return false;
    }
  }

  private String hmacSha256(String key, String data) throws Exception {
    Mac mac = Mac.getInstance(HMAC_SHA256);
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
    byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * 헤더 조회 시 대소문자 구분 없이 매칭한다 (API Gateway의 헤더 케이싱 비일관성 대응).
   */
  private String getHeader(Map<String, String> headers, String name) {
    if (headers == null) return null;
    if (headers.containsKey(name)) return headers.get(name);

    String lower = name.toLowerCase();
    return headers.entrySet().stream()
        .filter(e -> e.getKey().toLowerCase().equals(lower))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }
}
