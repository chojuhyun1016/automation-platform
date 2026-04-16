package com.riman.automation.worker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.common.util.SentryInitializer;
import com.riman.automation.worker.facade.AbsenceFacade;
import com.riman.automation.worker.facade.JiraIssueFacade;
import com.riman.automation.worker.facade.LunchCardFacade;
import com.riman.automation.worker.facade.RemoteWorkFacade;
import com.riman.automation.worker.facade.ScheduleFacade;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.LunchCardNotificationService;
import com.riman.automation.worker.service.ScheduleEventMappingService;
import com.riman.automation.worker.service.TeamMemberService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Worker Lambda 진입점. SQS 메시지를 수신하여 messageType에 따라 Facade로 디스패치한다.
 * 디스패치 매핑: remote_work → RemoteWorkFacade, absence → AbsenceFacade,
 * schedule → ScheduleFacade, 그 외(기본) → JiraIssueFacade.
 */
@Slf4j
public class WorkerHandler implements RequestHandler<SQSEvent, Void> {

  private static final String TYPE_REMOTE_WORK = "remote_work";
  private static final String TYPE_ABSENCE = "absence";
  private static final String TYPE_SCHEDULE = "schedule";
  private static final String TYPE_LUNCH_CARD = "lunch_card";
  private static final String TYPE_JIRA_WEBHOOK = "jira_webhook";

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule());

  static {
    SentryInitializer.init("worker");
  }

  private final JiraIssueFacade jiraFacade;
  private final RemoteWorkFacade remoteWorkFacade;
  private final AbsenceFacade absenceFacade;
  private final ScheduleFacade scheduleFacade;
  private final LunchCardFacade lunchCardFacade;

  /**
   * Facade와 공유 Service를 구성한다.
   * ConfigService를 한 번만 생성하여 S3 이중 로딩을 방지한다.
   */
  public WorkerHandler() {
    ConfigService configService = new ConfigService();
    CalendarService calendarService = new CalendarService(configService);
    TeamMemberService teamMemberService = new TeamMemberService();
    DedupeService dedupeService = new DedupeService();
    ScheduleEventMappingService scheduleMappingService = new ScheduleEventMappingService();

    this.jiraFacade = new JiraIssueFacade();
    this.remoteWorkFacade = new RemoteWorkFacade();
    // AbsenceFacade 4-arg 생성자. GroupwareMessageService.getInstance()는 내부에서 주입된다.
    this.absenceFacade = new AbsenceFacade(
        configService, calendarService, teamMemberService, dedupeService);
    this.scheduleFacade = new ScheduleFacade(
        configService, calendarService, dedupeService, scheduleMappingService);

    String lunchCardChannelId = configService.getLunchCardNotificationChannelId();
    LunchCardNotificationService lunchCardNotificationService =
        new LunchCardNotificationService(lunchCardChannelId);
    this.lunchCardFacade = new LunchCardFacade(
        configService, calendarService, teamMemberService, dedupeService,
        lunchCardNotificationService);

    log.info("WorkerHandler 초기화 완료 (Jira + RemoteWork + Absence + Schedule + LunchCard)");
  }

  /**
   * SQS 배치의 각 메시지를 dispatch()로 처리한다.
   * 한 건이라도 실패하면 RuntimeException을 던져 SQS 재시도/DLQ 경로로 보낸다.
   */
  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    List<String> successIds = new ArrayList<>();
    List<String> failedIds = new ArrayList<>();

    log.info("SQS 메시지 수신: count={}, requestId={}",
        event.getRecords().size(), context.getAwsRequestId());

    for (SQSMessage message : event.getRecords()) {
      try {
        dispatch(message);
        successIds.add(message.getMessageId());
      } catch (Exception e) {
        log.error("메시지 처리 실패: messageId={}", message.getMessageId(), e);
        SentryInitializer.captureException(e, "dispatch");
        SentryInitializer.flush();
        failedIds.add(message.getMessageId());
        throw new RuntimeException("Message processing failed: " + message.getMessageId(), e);
      }
    }

    log.info("처리 완료: success={}, failed={}", successIds.size(), failedIds.size());
    return null;
  }

  /**
   * messageType 값으로 대상 Facade를 선택해 처리를 위임한다.
   */
  private void dispatch(SQSMessage message) throws Exception {
    String body = message.getBody();
    String messageType = resolveMessageType(message, body);

    log.info("메시지 타입: {}, messageId={}", messageType, message.getMessageId());

    switch (messageType) {
      case TYPE_REMOTE_WORK:
        remoteWorkFacade.handle(body);
        break;
      case TYPE_ABSENCE:
        absenceFacade.handle(body);
        break;
      case TYPE_SCHEDULE:
        scheduleFacade.handle(body);
        break;
      case TYPE_LUNCH_CARD:
        lunchCardFacade.handle(body);
        break;
      default:
        jiraFacade.handle(body);
        break;
    }
  }

  /**
   * messageType 결정 순서.
   * 1) SQS MessageAttribute "messageType"
   * 2) JSON body의 "messageType" 필드
   * 3) 기본값 "jira_webhook"
   */
  private String resolveMessageType(SQSMessage message, String body) {
    if (message.getMessageAttributes() != null) {
      var attr = message.getMessageAttributes().get("messageType");
      if (attr != null && attr.getStringValue() != null) {
        return attr.getStringValue();
      }
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root.has("messageType")) return root.get("messageType").asText();
    } catch (Exception e) {
      log.warn("messageType JSON 파싱 실패, 기본값 사용: {}", e.getMessage());
    }
    return TYPE_JIRA_WEBHOOK;
  }
}
