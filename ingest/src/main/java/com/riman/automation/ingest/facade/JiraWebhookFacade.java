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
 * Jira Webhook Facade
 *
 * <pre>
 * 책임:
 *   1. 요청 body 파싱 (JiraWebhookEvent)
 *   2. 유효성 검증
 *   3. 메타데이터 추가 (eventId, receivedAt)
 *   4. SQS 전송
 * </pre>
 *
 * <p>SQSService는 싱글톤을 참조한다. ({@link WorkerMessageService#getInstance()})
 */
@Slf4j
public class JiraWebhookFacade {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // 싱글톤 참조 — new WorkerMessageService() 금지
    private final WorkerMessageService workerMessageService = WorkerMessageService.getInstance();

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

    private boolean isValid(JiraWebhookEvent event) {
        return event.getWebhookEvent() != null
                && event.getIssue() != null
                && event.getIssue().getKey() != null
                && event.getIssue().getFields() != null
                && event.getIssue().getFields().getProject() != null;
    }
}
