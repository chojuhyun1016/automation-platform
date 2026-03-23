package com.riman.automation.ingest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.ingest.dto.jira.JiraWebhookEvent;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class WorkerMessageService {

    private static final String MESSAGE_TYPE_JIRA = "jira_webhook";
    private static final String MESSAGE_TYPE_REMOTE_WORK = "remote_work";
    private static final String MESSAGE_TYPE_ABSENCE = "absence";
    private static final String MESSAGE_TYPE_SCHEDULE = "schedule";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final WorkerMessageService INSTANCE = new WorkerMessageService();

    public static WorkerMessageService getInstance() {
        return INSTANCE;
    }

    private static final SqsClient SQS_CLIENT = SqsClient.builder().build();

    private final String queueUrl;

    private WorkerMessageService() {
        this.queueUrl = System.getenv("SQS_QUEUE_URL");
        if (queueUrl == null || queueUrl.isEmpty()) {
            throw new ConfigException("환경변수 미설정: SQS_QUEUE_URL");
        }
        log.info("WorkerMessageService initialized: queue={}", queueUrl);
    }

    // =========================================================================
    // Jira Webhook
    // =========================================================================

    public String sendJiraEvent(JiraWebhookEvent event) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(event);
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_JIRA),
                            "projectKey", attr(event.getIssue().getFields().getProject().getKey()),
                            "issueKey", attr(event.getIssue().getKey())
                    ))
                    .build();
            String messageId = send(request);
            log.debug("Jira 이벤트 전송: messageId={}, issueKey={}",
                    messageId, event.getIssue().getKey());
            return messageId;
        } catch (Exception e) {
            log.error("Jira SQS 전송 실패: eventId={}", event.getEventId(), e);
            throw new ExternalApiClientException("SQS", "Jira 이벤트 전송 실패", e);
        }
    }

    // =========================================================================
    // 재택근무
    // =========================================================================

    public String sendRemoteWork(
            String slackUserId, String userName, String date, String action) {
        try {
            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("messageType", MESSAGE_TYPE_REMOTE_WORK);
            message.put("eventId", UUID.randomUUID().toString());
            message.put("receivedAt", Instant.now().toString());
            message.put("action", action);
            message.put("name", userName);
            message.put("date", date);
            message.put("slack_user_id", slackUserId);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message.toString())
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_REMOTE_WORK)
                    ))
                    .build();
            String messageId = send(request);
            log.debug("재택 전송: messageId={}, user={}, date={}", messageId, userName, date);
            return messageId;
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("재택 SQS 전송 실패: user={}", userName, e);
            throw new ExternalApiClientException("SQS", "재택근무 전송 실패", e);
        }
    }

    // =========================================================================
    // 부재등록
    // =========================================================================

    public String sendAbsence(
            String slackUserId, String userName,
            String absenceType, String action,
            String startDate, String endDate, String reason) {
        try {
            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("messageType", MESSAGE_TYPE_ABSENCE);
            message.put("eventId", UUID.randomUUID().toString());
            message.put("receivedAt", Instant.now().toString());
            message.put("slack_user_id", slackUserId);
            message.put("name", userName);
            message.put("absenceType", absenceType);
            message.put("action", action);
            message.put("startDate", startDate);
            message.put("endDate", endDate != null ? endDate : "");
            message.put("reason", reason != null ? reason : "");

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message.toString())
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_ABSENCE)
                    ))
                    .build();
            String messageId = send(request);
            log.debug("부재 전송: messageId={}, user={}, type={}, action={}, start={}, end={}",
                    messageId, userName, absenceType, action, startDate, endDate);
            return messageId;
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("부재 SQS 전송 실패: user={}", userName, e);
            throw new ExternalApiClientException("SQS", "부재등록 전송 실패", e);
        }
    }


    // =========================================================================
    // 일정등록
    // =========================================================================

    public String sendScheduleRegister(
            String slackUserId, String userName, String koreanName,
            String title, String description,
            String startDateTime, String endDateTime,
            java.util.List<Integer> reminderMinutes, String url) {
        try {
            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("messageType", MESSAGE_TYPE_SCHEDULE);
            message.put("action", "register");
            message.put("eventId", UUID.randomUUID().toString());
            message.put("receivedAt", Instant.now().toString());
            message.put("slackUserId", slackUserId);
            message.put("userName", userName);
            message.put("koreanName", koreanName != null ? koreanName : userName);
            message.put("title", title);
            message.put("description", description != null ? description : "");
            message.put("startDateTime", startDateTime);
            message.put("endDateTime", endDateTime != null ? endDateTime : "");
            message.put("url", url != null ? url : "");
            if (reminderMinutes != null && !reminderMinutes.isEmpty()) {
                message.set("reminderMinutes", OBJECT_MAPPER.valueToTree(reminderMinutes));
            }

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message.toString())
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_SCHEDULE),
                            "action", attr("register")
                    ))
                    .build();
            String messageId = send(request);
            log.debug("일정 등록 전송: messageId={}, user={}, title={}", messageId, userName, title);
            return messageId;
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("일정 등록 SQS 전송 실패: user={}, title={}", userName, title, e);
            throw new ExternalApiClientException("SQS", "일정 등록 전송 실패", e);
        }
    }

    public String sendScheduleDelete(String slackUserId, String calendarEventId) {
        try {
            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("messageType", MESSAGE_TYPE_SCHEDULE);
            message.put("action", "delete");
            message.put("eventId", UUID.randomUUID().toString());
            message.put("receivedAt", Instant.now().toString());
            message.put("slackUserId", slackUserId);
            message.put("calendarEventId", calendarEventId);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message.toString())
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_SCHEDULE),
                            "action", attr("delete")
                    ))
                    .build();
            String messageId = send(request);
            log.debug("일정 삭제 전송: messageId={}, user={}, calendarEventId={}",
                    messageId, slackUserId, calendarEventId);
            return messageId;
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("일정 삭제 SQS 전송 실패: user={}, calendarEventId={}",
                    slackUserId, calendarEventId, e);
            throw new ExternalApiClientException("SQS", "일정 삭제 전송 실패", e);
        }
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private String send(SendMessageRequest request) {
        SendMessageResponse response = SQS_CLIENT.sendMessage(request);
        return response.messageId();
    }

    private MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}
