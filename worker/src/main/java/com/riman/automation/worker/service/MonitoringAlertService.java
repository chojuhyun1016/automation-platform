package com.riman.automation.worker.service;

import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영 모니터링 Slack 알림 서비스.
 *
 * <p>Calendar API 쿼타 초과 등 운영 이슈 발생 시 Slack 알림을 전송한다.
 * {@code MONITORING_SLACK_CHANNEL} 환경변수가 미설정이면 알림을 건너뛴다.
 *
 * <p>싱글톤 패턴으로 사용. {@link #getInstance()}로 접근.
 */
@Slf4j
public class MonitoringAlertService {

    private static final MonitoringAlertService INSTANCE = new MonitoringAlertService();

    private final SlackClient slackClient;
    private final String monitoringChannel;
    private final boolean enabled;

    private MonitoringAlertService() {
        String channel = System.getenv("MONITORING_SLACK_CHANNEL");
        String botToken = System.getenv("SLACK_BOT_TOKEN");

        if (channel == null || channel.isBlank() || botToken == null || botToken.isBlank()) {
            log.info("[MonitoringAlertService] 비활성 (MONITORING_SLACK_CHANNEL 또는 SLACK_BOT_TOKEN 미설정)");
            this.slackClient = null;
            this.monitoringChannel = null;
            this.enabled = false;
            return;
        }

        this.slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
        this.monitoringChannel = channel;
        this.enabled = true;
        log.info("[MonitoringAlertService] 활성: channel={}", channel);
    }

    public static MonitoringAlertService getInstance() {
        return INSTANCE;
    }

    /**
     * Calendar API 쿼타 초과 경고를 Slack 채널에 전송한다.
     *
     * @param operation 실패한 작업명 (예: "listEvents", "insertEvent")
     * @param context   추가 컨텍스트 (예: calendarId)
     */
    public void alertCalendarQuotaExceeded(String operation, String context) {
        if (!enabled) {
            log.warn("[MonitoringAlertService] Calendar API 쿼타 초과 (알림 비활성): op={}, ctx={}",
                    operation, context);
            return;
        }

        try {
            String timestamp = DateTimeUtil.nowKst().toString();
            String payload = SlackBlockBuilder.forChannel(monitoringChannel)
                    .fallbackText("[경고] Calendar API 쿼타 초과")
                    .header("🚨 Calendar API 쿼타 초과 (429)")
                    .section(String.join("\n",
                            "*작업:* `" + operation + "`",
                            "*컨텍스트:* " + context,
                            "*감지 시각:* " + timestamp
                    ))
                    .context("Google Calendar API 일일 쿼타를 초과했습니다. 잠시 후 재시도하세요.")
                    .build();

            slackClient.postMessage(payload);
            log.info("[MonitoringAlertService] 쿼타 초과 알림 전송 완료: op={}", operation);
        } catch (Exception e) {
            log.error("[MonitoringAlertService] 쿼타 초과 알림 전송 실패", e);
        }
    }
}
