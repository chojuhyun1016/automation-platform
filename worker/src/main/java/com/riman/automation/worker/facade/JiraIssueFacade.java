package com.riman.automation.worker.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.SlackNotificationService;
import lombok.extern.slf4j.Slf4j;

/**
 * Jira 이슈 이벤트 처리 Facade
 *
 * <pre>
 * 역할: Jira Webhook 이벤트를 받아 서비스들을 조율
 *
 * 처리 순서:
 *   1. JSON 파싱 → JiraWebhookEvent
 *   2. 중복 체크 (DedupeService)
 *   3. 중복 방지 저장 (DedupeService)
 *   4. 라우팅 설정 조회 (ConfigService)
 *   5. Slack 알림 전송 (SlackNotificationService)
 *   6. Google Calendar 처리 (CalendarService)
 * </pre>
 */
@Slf4j
public class JiraIssueFacade {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final ConfigService configService;
    private final DedupeService dedupeService;
    private final SlackNotificationService slackNotificationService;
    private final CalendarService calendarService;

    public JiraIssueFacade() {
        this.configService = new ConfigService();
        this.dedupeService = new DedupeService();
        this.slackNotificationService = new SlackNotificationService();
        // ConfigService 인스턴스 재사용 → S3 이중 로드 방지
        this.calendarService = new CalendarService(configService);
    }

    public void handle(String body) throws Exception {
        // 1. 파싱
        JiraWebhookEvent event = objectMapper.readValue(body, JiraWebhookEvent.class);

        String eventId = event.getEventId();
        String issueKey = event.getIssue().getKey();
        String projectKey = event.getIssue().getFields().getProject().getKey();
        String webhookEvent = event.getWebhookEvent();
        long timestamp = event.getTimestamp() != null
                ? event.getTimestamp() : System.currentTimeMillis();

        log.info("Jira 이벤트 수신: eventId={}, issueKey={}, projectKey={}, webhookEvent={}",
                eventId, issueKey, projectKey, webhookEvent);

        // 2. 중복 체크
        if (dedupeService.isDuplicate(eventId, issueKey, timestamp)) {
            log.warn("중복 이벤트 무시: eventId={}", eventId);
            return;
        }

        // 3. 중복 방지 저장
        dedupeService.saveEvent(eventId, issueKey, timestamp);

        // 4. 라우팅 설정 조회
        ProjectRouting routing = configService.getRoutingConfig(projectKey);
        if (routing == null) {
            log.warn("라우팅 설정 없음: projectKey={}, 기본값 사용", projectKey);
            routing = configService.getDefaultRoutingConfig();
        }

        log.info("라우팅 적용: channelId={}, notification={}, calendar={}",
                routing.getSlackChannelId(),
                routing.getNotificationEnabled(),
                routing.getCalendarEnabled());

        // 5. Slack 알림
        if (Boolean.TRUE.equals(routing.getNotificationEnabled())) {
            try {
                slackNotificationService.sendNotification(event, routing);
                log.info("Slack 알림 완료: eventId={}", eventId);
            } catch (Exception e) {
                log.error("Slack 알림 실패 (계속 진행): eventId={}", eventId, e);
            }
        }

        // 6. Calendar 처리
        if (Boolean.TRUE.equals(routing.getCalendarEnabled())) {
            try {
                calendarService.processJiraEvent(event, routing);
                log.info("Calendar 처리 완료: eventId={}", eventId);
            } catch (Exception e) {
                log.error("Calendar 처리 실패 (계속 진행): eventId={}", eventId, e);
            }
        }

        log.info("Jira 이벤트 처리 완료: eventId={}", eventId);
    }
}
