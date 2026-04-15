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
 * 재택근무 신청/취소 Facade.
 * 비즈니스 로직은 RemoteWorkService에 위임하고, 이 클래스는 파싱/이름 해결/중복 방지/처리 호출만 조율한다.
 * 처리 순서: 파싱 → 한글 이름 조회 → 유효성 검증 → 중복 체크 → RemoteWorkService 위임 → 중복 방지 저장.
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
    // ConfigService 인스턴스 재사용: S3 이중 로드 방지
    this.remoteWorkService = new RemoteWorkService(calendarService, configService);
    this.dedupeService = new DedupeService();
    this.teamMemberService = new TeamMemberService();
  }

  public void handle(String body) throws Exception {
    RemoteWorkMessage msg = objectMapper.readValue(body, RemoteWorkMessage.class);

    String eventId = msg.getEventId() != null ? msg.getEventId() : "";
    String action = msg.getAction();
    String date = msg.getDate();
    String slackUserId = msg.getSlackUserId();

    // Slack username은 영문 ID이므로 slackUserId로 TeamMember를 찾아 한글 이름을 확보한다.
    String name = resolveKoreanName(slackUserId, msg.getName());

    log.info("재택 처리 시작: eventId={}, action={}, name={} (slackUserId={}), date={}",
        eventId, action, name, slackUserId, date);

    if (name == null || name.isBlank() || date == null || date.isBlank()) {
      log.warn("필수 필드 누락: name={}, date={}", name, date);
      return;
    }

    // REMOTE# prefix key로 재택 전용 중복 방지
    if (!eventId.isEmpty()) {
      String dedupeKey = "REMOTE#" + eventId;
      if (dedupeService.isDuplicateByKey(dedupeKey)) {
        log.warn("중복 이벤트 무시: eventId={}", eventId);
        return;
      }
    }

    remoteWorkService.process(action, name, date);

    if (!eventId.isEmpty()) {
      dedupeService.saveEventKey("REMOTE#" + eventId);
    }

    log.info("재택 처리 완료: eventId={}, name={}", eventId, name);
  }

  /**
   * Slack User ID로 TeamMember를 조회하여 한글 이름을 반환한다.
   * 조회 실패 시 fallback(보통 Slack user_name)을 반환한다.
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
