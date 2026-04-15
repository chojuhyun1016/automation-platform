package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.ingest.dto.jira.JiraWebhookEvent;
import com.riman.automation.ingest.service.WorkerMessageService;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Jira Webhook 요청을 처리하는 Facade.
 *
 * 책임: body 파싱 → 유효성 검증 → 메타데이터(eventId, receivedAt) 부여 → SQS 전송.
 * WorkerMessageService 는 {@link WorkerMessageService#getInstance()} 싱글톤을 사용한다.
 */
@Slf4j
public class JiraWebhookFacade {

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule());

  // 싱글톤 참조. new WorkerMessageService() 는 금지한다.
  private final WorkerMessageService workerMessageService = WorkerMessageService.getInstance();

  /**
   * Jira 웹훅 요청을 처리한다.
   * 성공 시 jiraAccepted(200), 구조 불량 시 400, 내부 오류 시 500 을 반환한다.
   */
  public APIGatewayProxyResponseEvent handle(String body, String requestId) {
    try {
      if (body == null || body.isEmpty()) {
        log.warn("Jira 요청 body 없음");
        return HttpResponse.badRequest("Empty request body");
      }

      JiraWebhookEvent event = objectMapper.readValue(body, JiraWebhookEvent.class);

      String eventId = UUID.randomUUID().toString();
      event.setEventId(eventId);
      event.setReceivedAt(Instant.now());

      log.info("Jira 이벤트 수신: eventId={}, webhookEvent={}, issueKey={}",
          eventId, event.getWebhookEvent(),
          event.getIssue() != null ? event.getIssue().getKey() : "N/A");

      if (!isValid(event)) {
        log.warn("유효하지 않은 이벤트 구조: eventId={}", eventId);
        return HttpResponse.badRequest("Invalid event structure");
      }

      String messageId = workerMessageService.sendJiraEvent(event);
      log.info("Jira 이벤트 SQS 전송 완료: messageId={}, eventId={}", messageId, eventId);

      return HttpResponse.jiraAccepted(objectMapper, eventId, messageId, requestId);

    } catch (Exception e) {
      log.error("Jira 이벤트 처리 실패", e);
      return HttpResponse.internalError();
    }
  }

  /**
   * webhookEvent, issue.key, issue.fields.project 의 존재를 최소 유효성 조건으로 검사한다.
   */
  private boolean isValid(JiraWebhookEvent event) {
    return event.getWebhookEvent() != null
        && event.getIssue() != null
        && event.getIssue().getKey() != null
        && event.getIssue().getFields() != null
        && event.getIssue().getFields().getProject() != null;
  }
}
