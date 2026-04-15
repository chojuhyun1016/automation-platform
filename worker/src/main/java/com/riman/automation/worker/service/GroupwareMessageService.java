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
 * 그룹웨어 부재 자동 신청 SQS 발행 서비스 (싱글톤).
 * GROUPWARE_SQS_QUEUE_URL 미설정 시 비활성 상태로 동작하며, 메시지에는 ID/PW를 절대 포함하지 않는다.
 * 자격증명은 Fargate Task가 Secrets Manager에서 직접 조회한다.
 */
@Slf4j
public class GroupwareMessageService {

  private static final String MESSAGE_TYPE_GROUPWARE = "groupware_absence";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());

  private static volatile SqsClient sqsClient;

  private static final GroupwareMessageService INSTANCE = new GroupwareMessageService();

  /**
   * SqsClient를 지연 초기화한다. Lambda 컨테이너 재사용 시 static volatile 캐싱으로 생성 비용을 아낀다.
   */
  private static SqsClient getSqsClient() {
    if (sqsClient == null) {
      sqsClient = SqsClient.builder().build();
    }
    return sqsClient;
  }

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
   * GROUPWARE_SQS_QUEUE_URL이 미설정이면 전송을 생략하고 null을 반환한다.
   *
   * @param slackUserId Slack 사용자 ID
   * @param memberName  한글 이름
   * @param team        팀 코드 (예: CCE)
   * @param role        역할 (Engineer / Manager)
   * @param absenceType 부재 유형. Slack /부재등록 전달값 그대로
   * @param action      "apply" 또는 "cancel"
   * @param startDate   시작일 (yyyy-MM-dd)
   * @param endDate     종료일 (yyyy-MM-dd)
   * @param reason      사유. 비어 있으면 "개인사유"로 설정된다
   * @return SQS messageId. 비활성 상태이면 null
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

      SendMessageResponse response = getSqsClient().sendMessage(request);
      log.info("[GroupwareMessageService] SQS 전송 완료: messageId={}, user={}, type={}, action={}",
          response.messageId(), memberName, absenceType, action);
      return response.messageId();

    } catch (Exception e) {
      log.error("[GroupwareMessageService] SQS 전송 실패: user={}", memberName, e);
      throw new ExternalApiClientException("SQS-Groupware", "그룹웨어 메시지 전송 실패", e);
    }
  }

  private MessageAttributeValue attr(String value) {
    return MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(value)
        .build();
  }
}
