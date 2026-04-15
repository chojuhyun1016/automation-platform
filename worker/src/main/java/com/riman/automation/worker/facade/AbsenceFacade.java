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
 * 부재등록 처리 Facade.
 * 처리 파이프라인: 파싱 → 한글 이름 해결 → 유효성 검증 → 중복 체크 → 날짜 보정 → 사유 보정 → 캘린더 처리 → 그룹웨어 SQS 발행(apply 한정) → 중복 방지 저장.
 * 캘린더 처리와 그룹웨어 발행은 실패해도 예외를 전파하지 않고 DLQ 방지를 우선한다.
 * absence.calendar_id 미설정 시 remoteWork.calendar_id로 폴백한다.
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
  private final GroupwareMessageService groupwareMessageService;

  /**
   * WorkerHandler가 사용하는 4-arg 생성자.
   * GroupwareMessageService는 싱글톤을 자동 주입한다.
   */
  public AbsenceFacade(
      ConfigService configService,
      CalendarService calendarService,
      TeamMemberService teamMemberService,
      DedupeService dedupeService) {
    this(configService, calendarService, teamMemberService, dedupeService,
        GroupwareMessageService.getInstance());
  }

  /**
   * GroupwareMessageService를 명시적으로 주입할 수 있는 생성자. 주로 테스트에서 사용한다.
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

  public void handle(String messageBody) {

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

    String koreanName = resolveKoreanName(msg);
    msg.setName(koreanName);

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

    String dedupeKey = DEDUPE_PREFIX + msg.getEventId();
    if (dedupeService.isDuplicateByKey(dedupeKey)) {
      log.info("중복 이벤트 → 스킵: eventId={}", msg.getEventId());
      return;
    }

    // 반차 등 날짜 1개 유형은 endDate를 startDate로 강제한다.
    if (msg.isSingleDayType()) {
      log.info("날짜 1개 유형 → endDate = startDate: type={}, date={}",
          msg.getAbsenceType(), msg.getStartDate());
      msg.setEndDate(msg.getStartDate());
    }

    // endDate가 비어있거나 startDate보다 이전이면 startDate로 보정한다.
    // 배경: Slack 모달 종료일 초기값이 today이므로 시작일만 바꾸면 endDate < startDate 역전이 발생할 수 있다.
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

    if (msg.getReason() == null || msg.getReason().isBlank()) {
      msg.setReason(DEFAULT_REASON);
      log.info("사유 공란 → '{}' 설정", DEFAULT_REASON);
    }

    processCalendar(msg);

    // 그룹웨어 자동 신청은 apply 한정. cancel은 그룹웨어 자동 취소가 불가능하여 groupware Lambda가 Slack 안내로 대체한다.
    if (msg.isApply()) {
      sendGroupwareIfEnabled(msg, koreanName);
    }

    try {
      dedupeService.saveEventKey(dedupeKey);
    } catch (Exception e) {
      log.warn("DedupeService 저장 실패 (무시): eventId={}", msg.getEventId(), e);
    }

    log.info("부재 처리 완료: eventId={}, user={}, type={}, action={}",
        msg.getEventId(), msg.getName(), msg.getAbsenceType(), msg.getAction());
  }

  /**
   * absence 캘린더 ID를 조회하여 AbsenceService에 처리를 위임한다.
   * 캘린더 처리 실패는 DLQ 방지를 위해 예외를 삼킨다.
   */
  private void processCalendar(AbsenceMessage msg) {
    try {
      String calendarId = configService.getAbsenceCalendarId();

      if (calendarId == null || calendarId.isBlank() || "primary".equals(calendarId)) {
        log.warn("유효한 absence 캘린더 ID 없음 → 스킵: calendarId={}", calendarId);
        return;
      }

      absenceService.process(calendarId, msg);

    } catch (Exception e) {
      log.error("부재 캘린더 처리 실패 (무시): user={}, type={}, action={}",
          msg.getName(), msg.getAbsenceType(), msg.getAction(), e);
    }
  }

  /**
   * 그룹웨어 부재 신청 SQS를 발행한다.
   * GROUPWARE_SQS_QUEUE_URL이 미설정이면 GroupwareMessageService가 내부에서 조용히 생략한다.
   * 발행 실패는 캘린더 처리 결과에 영향을 주지 않도록 예외를 삼킨다.
   */
  private void sendGroupwareIfEnabled(AbsenceMessage msg, String koreanName) {
    try {
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
          msg.getAbsenceType(),
          msg.getAction(),
          msg.getStartDate(),
          msg.getEndDate(),
          msg.getReason()
      );
    } catch (Exception e) {
      log.error("[AbsenceFacade] 그룹웨어 SQS 발행 실패(무시): user={}, err={}",
          koreanName, e.getMessage());
    }
  }

  /**
   * slackUserId로 TeamMember를 조회해 한글 이름을 반환한다.
   * TeamMember가 없으면 메시지의 name(영문 username 폴백)을 반환한다.
   */
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
