package com.riman.automation.groupware.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.groupware.dto.GroupwareAbsenceMessage;
import com.riman.automation.groupware.facade.GroupwareAbsenceFacade;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 그룹웨어 부재 자동 신청 Lambda Handler.
 *
 * <pre>
 * SQS 트리거 → GroupwareAbsenceFacade → ECS Fargate Task(Playwright) 실행
 * </pre>
 */
@Slf4j
public class GroupwareHandler implements RequestHandler<SQSEvent, Void> {

    private static final ObjectMapper OM = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final GroupwareAbsenceFacade FACADE = new GroupwareAbsenceFacade();

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        log.info("[GroupwareHandler] SQS 메시지 수신: count={}, requestId={}",
                event.getRecords().size(), context.getAwsRequestId());

        for (SQSMessage msg : event.getRecords()) {
            try {
                GroupwareAbsenceMessage absence =
                        OM.readValue(msg.getBody(), GroupwareAbsenceMessage.class);
                log.info("[GroupwareHandler] 처리: user={}, type={}, {}~{}",
                        absence.getMemberName(), absence.getAbsenceType(),
                        absence.getStartDate(), absence.getEndDate());
                FACADE.handle(absence);
                successIds.add(msg.getMessageId());
            } catch (Exception e) {
                log.error("[GroupwareHandler] 처리 실패: messageId={}", msg.getMessageId(), e);
                failedIds.add(msg.getMessageId());
                // SQS 재시도 트리거를 위해 RuntimeException으로 던짐
                throw new RuntimeException("Groupware processing failed: " + msg.getMessageId(), e);
            }
        }

        log.info("[GroupwareHandler] 완료: success={}, failed={}",
                successIds.size(), failedIds.size());
        return null;
    }
}
