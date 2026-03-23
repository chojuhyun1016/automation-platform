package com.riman.automation.worker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.worker.facade.AbsenceFacade;
import com.riman.automation.worker.facade.JiraIssueFacade;
import com.riman.automation.worker.facade.RemoteWorkFacade;
import com.riman.automation.worker.facade.ScheduleFacade;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.ScheduleEventMappingService;
import com.riman.automation.worker.service.TeamMemberService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * SQS Worker Handler
 *
 * <pre>
 * 메시지 타입 분기:
 *   remote_work  → RemoteWorkFacade
 *   absence      → AbsenceFacade
 *   schedule     → ScheduleFacade  ← 신규
 *   jira_webhook → JiraIssueFacade (기본)
 * </pre>
 */
@Slf4j
public class WorkerHandler implements RequestHandler<SQSEvent, Void> {

    private static final String TYPE_REMOTE_WORK = "remote_work";
    private static final String TYPE_ABSENCE = "absence";
    private static final String TYPE_SCHEDULE = "schedule";       // ← 추가
    private static final String TYPE_JIRA_WEBHOOK = "jira_webhook";

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final JiraIssueFacade jiraFacade;
    private final RemoteWorkFacade remoteWorkFacade;
    private final AbsenceFacade absenceFacade;
    private final ScheduleFacade scheduleFacade;               // ← 추가

    public WorkerHandler() {
        ConfigService configService = new ConfigService();
        CalendarService calendarService = new CalendarService(configService);
        TeamMemberService teamMemberService = new TeamMemberService();
        DedupeService dedupeService = new DedupeService();
        ScheduleEventMappingService scheduleMappingService = new ScheduleEventMappingService(); // ← 추가

        this.jiraFacade = new JiraIssueFacade();
        this.remoteWorkFacade = new RemoteWorkFacade();
        // AbsenceFacade 4-arg 생성자 — GroupwareMessageService.getInstance() 자동 주입됨
        this.absenceFacade = new AbsenceFacade(
                configService, calendarService, teamMemberService, dedupeService);
        this.scheduleFacade = new ScheduleFacade(              // ← 추가
                configService, calendarService, dedupeService, scheduleMappingService);

        log.info("WorkerHandler 초기화 완료 (Jira + RemoteWork + Absence + Schedule)");
    }

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
                failedIds.add(message.getMessageId());
                throw new RuntimeException("Message processing failed: " + message.getMessageId(), e);
            }
        }

        log.info("처리 완료: success={}, failed={}", successIds.size(), failedIds.size());
        return null;
    }

    // =========================================================================
    // 내부
    // =========================================================================

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
            case TYPE_SCHEDULE:                    // ← 핵심 추가
                scheduleFacade.handle(body);
                break;
            default:
                jiraFacade.handle(body);
                break;
        }
    }

    private String resolveMessageType(SQSMessage message, String body) {
        // 1순위: SQS MessageAttribute
        if (message.getMessageAttributes() != null) {
            var attr = message.getMessageAttributes().get("messageType");
            if (attr != null && attr.getStringValue() != null) {
                return attr.getStringValue();
            }
        }
        // 2순위: Body JSON
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("messageType")) return root.get("messageType").asText();
        } catch (Exception e) {
            log.warn("messageType JSON 파싱 실패, 기본값 사용: {}", e.getMessage());
        }
        return TYPE_JIRA_WEBHOOK;
    }
}
