package com.riman.automation.worker.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.dto.sqs.AbsenceMessage;
import com.riman.automation.worker.service.AbsenceService;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.GroupwareMessageService;
import com.riman.automation.worker.service.TeamMemberService;
import lombok.extern.slf4j.Slf4j;

/**
 * 부재등록 처리 Facade
 *
 * <p>RemoteWorkFacade와 동일한 설계 패턴을 따른다.
 *
 * <pre>
 * 처리 흐름:
 *   1. JSON 파싱 → AbsenceMessage
 *   2. TeamMemberService: slackUserId → 한글 이름 조회 (폴백: msg.getName())
 *   3. 유효성 검증 (이름, 시작일, 부재 유형, 액션)
 *   4. DedupeService 중복 체크 ("ABSENCE#" + eventId)
 *   5. 날짜 1개 유형이면 endDate = startDate 강제
 *   6. 사유 공란이면 "개인사유" 설정
 *   7. AbsenceService.process() → 캘린더 처리
 *   8. (신규) apply 액션이면 그룹웨어 SQS 발행 (GroupwareMessageService)
 *   9. DedupeService 저장
 * </pre>
 *
 * <p>캘린더 처리 실패는 로그만 남기고 진행 (DLQ 방지).
 * absence.calendar_id 미설정 시 remoteWork.calendar_id로 폴백.
 */
@Slf4j
public class AbsenceFacade {

    private static final String DEDUPE_PREFIX = "ABSENCE#";
    private static final String DEFAULT_REASON = "개인사유";

    private final ObjectMapper objectMapper;
    private final ConfigService configService;
    private final TeamMemberService teamMemberService;
    private final DedupeService dedupeService;
    private final AbsenceService absenceService;
    private final GroupwareMessageService groupwareMessageService; // ← 추가

    /**
     * WorkerHandler에서 생성 — 기존 4-arg 생성자 유지 (하위 호환).
     * GroupwareMessageService를 사용하지 않는 경우 (예: 테스트)에 사용.
     */
    public AbsenceFacade(
            ConfigService configService,
            CalendarService calendarService,
            TeamMemberService teamMemberService,
            DedupeService dedupeService) {
        this(configService, calendarService, teamMemberService, dedupeService,
                GroupwareMessageService.getInstance()); // Singleton 자동 주입
    }

    /**
     * GroupwareMessageService 명시 주입용 생성자 (테스트 등에서 사용).
     */
    public AbsenceFacade(
            ConfigService configService,
            CalendarService calendarService,
            TeamMemberService teamMemberService,
            DedupeService dedupeService,
            GroupwareMessageService groupwareMessageService) {
        this.objectMapper = new ObjectMapper();
        this.configService = configService;
        this.teamMemberService = teamMemberService;
        this.dedupeService = dedupeService;
        this.absenceService = new AbsenceService(calendarService);
        this.groupwareMessageService = groupwareMessageService;
    }

    // =========================================================================
    // 진입점
    // =========================================================================

    public void handle(String messageBody) {

        // 1. 파싱
        AbsenceMessage msg;
        try {
            msg = objectMapper.readValue(messageBody, AbsenceMessage.class);
        } catch (Exception e) {
            log.error("AbsenceMessage 파싱 실패: body={}", messageBody, e);
            throw new RuntimeException("AbsenceMessage 파싱 실패", e);
        }

        log.info("부재 처리 시작: eventId={}, user={}, type={}, action={}, start={}, end={}",
                msg.getEventId(), msg.getName(), msg.getAbsenceType(),
                msg.getAction(), msg.getStartDate(), msg.getEndDate());

        // 2. 한글 이름 조회
        String koreanName = resolveKoreanName(msg);
        msg.setName(koreanName);

        // 3. 유효성 검증
        if (koreanName == null || koreanName.isBlank()) {
            log.warn("이름 없음 → 스킵: eventId={}", msg.getEventId());
            return;
        }
        if (msg.getStartDate() == null || msg.getStartDate().isBlank()) {
            log.warn("시작일 없음 → 스킵: eventId={}", msg.getEventId());
            return;
        }
        if (msg.getAbsenceType() == null || msg.getAbsenceType().isBlank()) {
            log.warn("부재 유형 없음 → 스킵: eventId={}", msg.getEventId());
            return;
        }
        if (!msg.isApply() && !msg.isCancel()) {
            log.warn("알 수 없는 action → 스킵: action={}, eventId={}", msg.getAction(), msg.getEventId());
            return;
        }

        // 4. 중복 체크
        String dedupeKey = DEDUPE_PREFIX + msg.getEventId();
        if (dedupeService.isDuplicateByKey(dedupeKey)) {
            log.info("중복 이벤트 → 스킵: eventId={}", msg.getEventId());
            return;
        }

        // 5. 날짜 1개 유형이면 endDate = startDate
        if (msg.isSingleDayType()) {
            log.info("날짜 1개 유형 → endDate = startDate: type={}, date={}",
                    msg.getAbsenceType(), msg.getStartDate());
            msg.setEndDate(msg.getStartDate());
        }

        // 5-1. endDate가 비어있거나 startDate보다 이전이면 startDate로 보정
        //      원인: Slack 모달 종료일 초기값이 today이므로
        //      사용자가 시작일만 바꾸고 종료일을 그대로 두면 endDate < startDate 역전 발생
        if (msg.getEndDate() == null || msg.getEndDate().isBlank()) {
            log.info("endDate 미입력 → startDate로 보정: date={}", msg.getStartDate());
            msg.setEndDate(msg.getStartDate());
        } else {
            try {
                java.time.LocalDate sd = java.time.LocalDate.parse(msg.getStartDate());
                java.time.LocalDate ed = java.time.LocalDate.parse(msg.getEndDate());
                if (ed.isBefore(sd)) {
                    log.warn("endDate({}) < startDate({}) 역전 감지 → startDate로 보정 (모달 종료일 미변경 추정)",
                            msg.getEndDate(), msg.getStartDate());
                    msg.setEndDate(msg.getStartDate());
                }
            } catch (Exception e) {
                log.warn("날짜 파싱 실패 → endDate 원본 유지: start={}, end={}",
                        msg.getStartDate(), msg.getEndDate());
            }
        }

        // 6. 사유 공란 처리
        if (msg.getReason() == null || msg.getReason().isBlank()) {
            msg.setReason(DEFAULT_REASON);
            log.info("사유 공란 → '{}' 설정", DEFAULT_REASON);
        }

        // 7. 캘린더 처리
        processCalendar(msg);

        // 8. apply 액션이면 그룹웨어 부재 신청 SQS 발행 ← 신규
        if (msg.isApply()) {
            sendGroupwareIfEnabled(msg, koreanName);
        }

        // 9. 중복방지 저장
        try {
            dedupeService.saveEventKey(dedupeKey);
        } catch (Exception e) {
            log.warn("DedupeService 저장 실패 (무시): eventId={}", msg.getEventId(), e);
        }

        log.info("부재 처리 완료: eventId={}, user={}, type={}, action={}",
                msg.getEventId(), msg.getName(), msg.getAbsenceType(), msg.getAction());
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private void processCalendar(AbsenceMessage msg) {
        try {
            String calendarId = configService.getAbsenceCalendarId();

            if (calendarId == null || calendarId.isBlank() || "primary".equals(calendarId)) {
                log.warn("유효한 absence 캘린더 ID 없음 → 스킵: calendarId={}", calendarId);
                return;
            }

            absenceService.process(calendarId, msg);

        } catch (Exception e) {
            // 캘린더 실패는 DLQ 방지를 위해 로그만 남김
            log.error("부재 캘린더 처리 실패 (무시): user={}, type={}, action={}",
                    msg.getName(), msg.getAbsenceType(), msg.getAction(), e);
        }
    }

    /**
     * 그룹웨어 SQS 발행.
     * <p>
     * GROUPWARE_SQS_QUEUE_URL 미설정이면 GroupwareMessageService 내부에서 조용히 생략.
     * 실패해도 캘린더 처리 결과에 영향 없음 (예외 삼킴, DLQ 방지).
     * cancel은 그룹웨어 자동 취소가 불가능하여 groupware Lambda에서 Slack 안내로 대체.
     */
    private void sendGroupwareIfEnabled(AbsenceMessage msg, String koreanName) {
        try {
            // TeamMember에서 team, role 조회 (이미 resolveKoreanName에서 조회했으나 재조회)
            String team = "CCE";
            String role = "Engineer";
            TeamMember member = teamMemberService.findBySlackUserId(msg.getSlackUserId());
            if (member != null) {
                if (member.getTeam() != null && !member.getTeam().isBlank()) {
                    team = member.getTeam();
                }
                if (member.getRole() != null && !member.getRole().isBlank()) {
                    role = member.getRole();
                }
            }

            groupwareMessageService.sendGroupwareAbsence(
                    msg.getSlackUserId(),
                    koreanName,
                    team,
                    role,
                    msg.getAbsenceType(),       // Slack /부재등록 전달값 그대로
                    msg.getAction(),
                    msg.getStartDate(),
                    msg.getEndDate(),
                    msg.getReason()
            );
        } catch (Exception e) {
            // 그룹웨어 SQS 실패는 캘린더 처리에 영향 없음 (DLQ 방지)
            log.error("[AbsenceFacade] 그룹웨어 SQS 발행 실패(무시): user={}, err={}",
                    koreanName, e.getMessage());
        }
    }

    private String resolveKoreanName(AbsenceMessage msg) {
        if (msg.getSlackUserId() != null && !msg.getSlackUserId().isBlank()) {
            TeamMember member = teamMemberService.findBySlackUserId(msg.getSlackUserId());
            if (member != null && member.getName() != null && !member.getName().isBlank()) {
                log.info("한글 이름 조회: slackUserId={} → {}",
                        msg.getSlackUserId(), member.getName());
                return member.getName();
            }
        }
        log.info("TeamMember 미조회 → SQS name 사용: name={}", msg.getName());
        return msg.getName();
    }
}
