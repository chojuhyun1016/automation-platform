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
 * Jira 웹훅 이벤트 처리 Facade.
 * 처리 순서: JSON 파싱 → 중복 체크/저장 → 라우팅 설정 조회 → Slack 알림 → Calendar 처리.
 * Slack 알림과 Calendar 처리는 서로 독립적으로 실패해도 다음 단계를 계속 진행한다.
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
    // ConfigService 인스턴스 재사용: S3 이중 로드 방지
    this.calendarService = new CalendarService(configService);
  }

  public void handle(String body) throws Exception {
    JiraWebhookEvent event = objectMapper.readValue(body, JiraWebhookEvent.class);

    String eventId = event.getEventId();
    String issueKey = event.getIssue().getKey();
    String projectKey = event.getIssue().getFields().getProject().getKey();
    String webhookEvent = event.getWebhookEvent();
    long timestamp = event.getTimestamp() != null
        ? event.getTimestamp() : System.currentTimeMillis();

    log.info("Jira 이벤트 수신: eventId={}, issueKey={}, projectKey={}, webhookEvent={}",
        eventId, issueKey, projectKey, webhookEvent);

    if (dedupeService.isDuplicate(eventId, issueKey, timestamp)) {
      log.warn("중복 이벤트 무시: eventId={}", eventId);
      return;
    }

    dedupeService.saveEvent(eventId, issueKey, timestamp);

    // 프로젝트별 라우팅 없으면 defaultConfig로 폴백
    ProjectRouting routing = configService.getRoutingConfig(projectKey);
    if (routing == null) {
      log.warn("라우팅 설정 없음: projectKey={}, 기본값 사용", projectKey);
      routing = configService.getDefaultRoutingConfig();
    }

    log.info("라우팅 적용: channelId={}, notification={}, calendar={}",
        routing.getSlackChannelId(),
        routing.getNotificationEnabled(),
        routing.getCalendarEnabled());

    // Slack 알림: 실패해도 Calendar 처리에 영향 없음
    if (Boolean.TRUE.equals(routing.getNotificationEnabled())) {
      try {
        slackNotificationService.sendNotification(event, routing);
        log.info("Slack 알림 완료: eventId={}", eventId);
      } catch (Exception e) {
        log.error("Slack 알림 실패 (계속 진행): eventId={}", eventId, e);
      }
    }

    // Calendar 처리: 실패해도 전체 처리는 성공으로 간주 (DLQ 방지)
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
