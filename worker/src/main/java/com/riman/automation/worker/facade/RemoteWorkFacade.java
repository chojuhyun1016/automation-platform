package com.riman.automation.worker.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riman.automation.worker.dto.sqs.RemoteWorkMessage;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.RemoteWorkService;
import com.riman.automation.worker.service.TeamMemberService;
import com.riman.automation.worker.dto.s3.TeamMember;
import lombok.extern.slf4j.Slf4j;

/**
 * 재택근무 신청/취소 Facade
 *
 * <pre>
 * 역할: 서비스들을 조율 (비즈니스 로직은 RemoteWorkService에 위임)
 *
 * 처리 순서:
 *   1. JSON 파싱 → RemoteWorkMessage
 *   2. 한글 이름 조회 (slackUserId → TeamMemberService)
 *   3. 유효성 검증
 *   4. 중복 체크 (DedupeService)
 *   5. 재택 신청/취소 처리 (RemoteWorkService)
 *   6. 중복 방지 저장 (DedupeService)
 * </pre>
 */
@Slf4j
public class RemoteWorkFacade {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final DedupeService dedupeService;
    private final RemoteWorkService remoteWorkService;
    private final TeamMemberService teamMemberService;

    public RemoteWorkFacade() {
        ConfigService configService = new ConfigService();
        CalendarService calendarService = new CalendarService(configService);
        // ConfigService 인스턴스 재사용 → S3 이중 로드 방지
        this.remoteWorkService = new RemoteWorkService(calendarService, configService);
        this.dedupeService = new DedupeService();
        this.teamMemberService = new TeamMemberService();
    }

    public void handle(String body) throws Exception {
        // 1. 파싱
        RemoteWorkMessage msg = objectMapper.readValue(body, RemoteWorkMessage.class);

        String eventId = msg.getEventId() != null ? msg.getEventId() : "";
        String action = msg.getAction();
        String date = msg.getDate();
        String slackUserId = msg.getSlackUserId();

        // 2. 한글 이름 조회: slackUserId → TeamMemberService → 한글 이름
        //    조회 실패 시 msg.getName() (영문 username) 으로 폴백
        String name = resolveKoreanName(slackUserId, msg.getName());

        log.info("재택 처리 시작: eventId={}, action={}, name={} (slackUserId={}), date={}",
                eventId, action, name, slackUserId, date);

        // 3. 유효성 검증
        if (name == null || name.isBlank() || date == null || date.isBlank()) {
            log.warn("필수 필드 누락: name={}, date={}", name, date);
            return;
        }

        // 4. 중복 체크
        if (!eventId.isEmpty()) {
            String dedupeKey = "REMOTE#" + eventId;
            if (dedupeService.isDuplicateByKey(dedupeKey)) {
                log.warn("중복 이벤트 무시: eventId={}", eventId);
                return;
            }
        }

        // 5. 재택 처리 위임 (신청/취소 비즈니스 로직은 RemoteWorkService 담당)
        remoteWorkService.process(action, name, date);

        // 6. 중복 방지 저장
        if (!eventId.isEmpty()) {
            dedupeService.saveEventKey("REMOTE#" + eventId);
        }

        log.info("재택 처리 완료: eventId={}, name={}", eventId, name);
    }

    // =========================================================================
    // 내부 헬퍼
    // =========================================================================

    /**
     * Slack User ID → TeamMember → 한글 이름
     * <p>
     * Slack user_name은 영문 ID(juhyun.cho)이므로 사용하지 않는다.
     * slackUserId(U0627755JP7)로 TeamMember를 찾아 name(조주현)을 반환한다.
     *
     * @param slackUserId Slack User ID (예: U0627755JP7)
     * @param fallback    조회 실패 시 사용할 이름 (Slack user_name)
     * @return 한글 이름, 조회 실패 시 fallback
     */
    private String resolveKoreanName(String slackUserId, String fallback) {
        if (slackUserId == null || slackUserId.isBlank()) {
            log.warn("slackUserId 없음, fallback 이름 사용: {}", fallback);
            return fallback;
        }

        try {
            TeamMember member = teamMemberService.findBySlackUserId(slackUserId);
            if (member != null && member.getName() != null && !member.getName().isBlank()) {
                log.info("한글 이름 조회 성공: slackUserId={} → name={}", slackUserId, member.getName());
                return member.getName();
            }
            log.warn("TeamMember 없음 또는 이름 비어있음: slackUserId={}, fallback={}", slackUserId, fallback);
        } catch (Exception e) {
            log.warn("한글 이름 조회 실패, fallback 사용: slackUserId={}, fallback={}", slackUserId, fallback, e);
        }

        return fallback;
    }
}
