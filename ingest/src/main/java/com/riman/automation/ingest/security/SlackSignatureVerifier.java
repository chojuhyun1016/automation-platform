package com.riman.automation.ingest.security;

import com.riman.automation.common.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    public boolean verify(Map<String, String> headers, String body) {
        try {
            String timestamp = getHeader(headers, "X-Slack-Request-Timestamp");
            String signature = getHeader(headers, "X-Slack-Signature");

            if (timestamp == null || signature == null) {
                log.warn("Slack 서명 헤더 없음");
                return false;
            }

            long requestTime = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - requestTime) > MAX_AGE_SECONDS) {
                log.warn("타임스탬프 만료: requestTime={}, now={}", requestTime, now);
                return false;
            }

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
