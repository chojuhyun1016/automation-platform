package com.riman.automation.worker.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.dto.sqs.LunchCardMessage;
import com.riman.automation.worker.service.CalendarService;
import com.riman.automation.worker.service.ConfigService;
import com.riman.automation.worker.service.DedupeService;
import com.riman.automation.worker.service.LunchCardNotificationService;
import com.riman.automation.worker.service.LunchCardService;
import com.riman.automation.worker.service.TeamMemberService;
import lombok.extern.slf4j.Slf4j;

/**
 * 점심카드 처리 Facade.
 * 처리 파이프라인: 파싱 → 한글 이름 해결 → 유효성 검증 → 중복 체크 → 캘린더 처리 → 알림 → 중복 방지 저장.
 * 캘린더 처리와 알림은 실패해도 예외를 전파하지 않고 DLQ 방지를 우선한다.
 */
@Slf4j
public class LunchCardFacade {

  private static final String DEDUPE_PREFIX = "LUNCH_CARD#";

  private final ObjectMapper objectMapper;
  private final ConfigService configService;
  private final TeamMemberService teamMemberService;
  private final DedupeService dedupeService;
  private final LunchCardService lunchCardService;
  private final LunchCardNotificationService notificationService;

  /**
   * WorkerHandler가 사용하는 생성자.
   */
  public LunchCardFacade(
      ConfigService configService,
      CalendarService calendarService,
      TeamMemberService teamMemberService,
      DedupeService dedupeService,
      LunchCardNotificationService notificationService) {
    this.objectMapper = new ObjectMapper();
    this.configService = configService;
    this.teamMemberService = teamMemberService;
    this.dedupeService = dedupeService;
    this.lunchCardService = new LunchCardService(calendarService);
    this.notificationService = notificationService;
  }

  public void handle(String messageBody) {

    LunchCardMessage msg;
    try {
      msg = objectMapper.readValue(messageBody, LunchCardMessage.class);
    } catch (Exception e) {
      log.error("LunchCardMessage 파싱 실패: body={}", messageBody, e);
      throw new RuntimeException("LunchCardMessage 파싱 실패", e);
    }

    log.info("점심카드 처리 시작: eventId={}, user={}, action={}, date={}",
        msg.getEventId(), msg.getName(), msg.getAction(), msg.getDate());

    String koreanName = resolveKoreanName(msg);
    msg.setName(koreanName);

    if (koreanName == null || koreanName.isBlank()) {
      log.warn("이름 없음 → 스킵: eventId={}", msg.getEventId());
      return;
    }
    if (msg.getDate() == null || msg.getDate().isBlank()) {
      log.warn("날짜 없음 → 스킵: eventId={}", msg.getEventId());
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

    processCalendar(msg);

    sendNotification(msg);

    try {
      dedupeService.saveEventKey(dedupeKey);
    } catch (Exception e) {
      log.warn("DedupeService 저장 실패 (무시): eventId={}", msg.getEventId(), e);
    }

    log.info("점심카드 처리 완료: eventId={}, user={}, action={}",
        msg.getEventId(), msg.getName(), msg.getAction());
  }

  /**
   * 점심카드 캘린더 ID를 조회하여 LunchCardService에 처리를 위임한다.
   * 캘린더 처리 실패는 DLQ 방지를 위해 예외를 삼킨다.
   */
  private void processCalendar(LunchCardMessage msg) {
    try {
      String calendarId = configService.getLunchCardCalendarId();

      if (calendarId == null || calendarId.isBlank() || "primary".equals(calendarId)) {
        log.warn("유효한 점심카드 캘린더 ID 없음 → 스킵: calendarId={}", calendarId);
        return;
      }

      if (msg.isApply()) {
        lunchCardService.applyLunchCard(calendarId, msg.getName(), msg.getDate());
      } else {
        lunchCardService.cancelLunchCard(calendarId, msg.getName(), msg.getDate());
      }

    } catch (Exception e) {
      log.error("점심카드 캘린더 처리 실패 (무시): user={}, action={}",
          msg.getName(), msg.getAction(), e);
    }
  }

  /**
   * 점심카드 신청/취소 알림을 전송한다.
   * 알림 실패는 캘린더 처리 결과에 영향을 주지 않도록 예외를 삼킨다.
   */
  private void sendNotification(LunchCardMessage msg) {
    try {
      notificationService.sendNotification(msg.getName(), msg.getAction(), msg.getDate());
    } catch (Exception e) {
      log.error("[LunchCardFacade] 알림 전송 실패 (무시): user={}, err={}",
          msg.getName(), e.getMessage());
    }
  }

  /**
   * slackUserId로 TeamMember를 조회해 한글 이름을 반환한다.
   * TeamMember가 없으면 메시지의 name(영문 username 폴백)을 반환한다.
   */
  private String resolveKoreanName(LunchCardMessage msg) {
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
