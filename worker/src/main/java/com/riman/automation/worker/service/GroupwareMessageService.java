package com.riman.automation.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.common.exception.ExternalApiClientException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 그룹웨어 부재 자동 신청 SQS 발행 서비스.
 *
 * <pre>
 * - ingest 모듈의 WorkerMessageService와 독립적으로 worker 모듈에 위치
 * - GROUPWARE_SQS_QUEUE_URL 미설정 시 경고 로그만 남기고 null 반환 (비활성화)
 * - ID/PW는 절대 포함하지 않으며, Fargate Task 내부에서 Secrets Manager 직접 조회
 * - absenceType은 Slack /부재등록에서 전달받은 문자열 그대로 사용
 * </pre>
 */
@Slf4j
public class GroupwareMessageService {

    private static final String MESSAGE_TYPE_GROUPWARE = "groupware_absence";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final SqsClient SQS_CLIENT = SqsClient.builder().build();

    private static final GroupwareMessageService INSTANCE = new GroupwareMessageService();

    public static GroupwareMessageService getInstance() {
        return INSTANCE;
    }

    private GroupwareMessageService() {
        String url = System.getenv("GROUPWARE_SQS_QUEUE_URL");
        if (url == null || url.isBlank()) {
            log.warn("[GroupwareMessageService] GROUPWARE_SQS_QUEUE_URL 미설정 — 그룹웨어 자동화 비활성화");
        } else {
            log.info("[GroupwareMessageService] initialized: queue={}", url);
        }
    }

    /**
     * 그룹웨어 부재 신청 메시지를 별도 SQS 큐로 전송한다.
     *
     * @param slackUserId Slack 사용자 ID
     * @param memberName  한글 이름
     * @param team        팀 코드 (예: CCE)
     * @param role        역할 (Engineer / Manager)
     * @param absenceType 부재 유형 — Slack /부재등록 전달값 그대로
     * @param action      apply / cancel
     * @param startDate   시작일 (yyyy-MM-dd)
     * @param endDate     종료일 (yyyy-MM-dd)
     * @param reason      사유
     * @return SQS messageId, GROUPWARE_SQS_QUEUE_URL 미설정 시 null
     */
    public String sendGroupwareAbsence(
            String slackUserId,
            String memberName,
            String team,
            String role,
            String absenceType,
            String action,
            String startDate,
            String endDate,
            String reason) {
        try {
            String groupwareQueueUrl = System.getenv("GROUPWARE_SQS_QUEUE_URL");
            if (groupwareQueueUrl == null || groupwareQueueUrl.isBlank()) {
                log.warn("[GroupwareMessageService] GROUPWARE_SQS_QUEUE_URL 미설정 — 전송 생략");
                return null;
            }

            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("messageType", MESSAGE_TYPE_GROUPWARE);
            message.put("eventId", UUID.randomUUID().toString());
            message.put("receivedAt", Instant.now().toString());
            message.put("slackUserId", slackUserId);
            message.put("memberName", memberName);
            message.put("team", team);
            message.put("role", role);
            message.put("absenceType", absenceType);
            message.put("action", action);
            message.put("startDate", startDate);
            message.put("endDate", endDate != null ? endDate : startDate);
            message.put("reason", reason != null && !reason.isBlank() ? reason : "개인사유");

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(groupwareQueueUrl)
                    .messageBody(message.toString())
                    .messageAttributes(Map.of(
                            "messageType", attr(MESSAGE_TYPE_GROUPWARE),
                            "action", attr(action)
                    ))
                    .build();

            SendMessageResponse response = SQS_CLIENT.sendMessage(request);
            log.info("[GroupwareMessageService] SQS 전송 완료: messageId={}, user={}, type={}, action={}",
                    response.messageId(), memberName, absenceType, action);
            return response.messageId();

        } catch (Exception e) {
            log.error("[GroupwareMessageService] SQS 전송 실패: user={}", memberName, e);
            throw new ExternalApiClientException("SQS-Groupware", "그룹웨어 메시지 전송 실패", e);
        }
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}
