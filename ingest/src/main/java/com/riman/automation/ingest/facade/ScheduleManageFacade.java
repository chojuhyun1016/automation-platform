package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.AutomationException;
import com.riman.automation.ingest.dto.slack.ScheduleModalSubmit;
import com.riman.automation.ingest.service.ScheduleMappingQueryService;
import com.riman.automation.ingest.service.SlackApiService;
import com.riman.automation.ingest.service.WorkerMessageService;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * /일정등록 커맨드 및 연관 모달/버튼 처리 Facade.
 *
 * 세 가지 진입점으로 구성된다.
 * 1. handleCommand()     — /일정등록 커맨드 수신 시 본인 일정 유무에 따라 모달을 분기한다.
 * 2. handleModalSubmit() — schedule_submit view_submission 처리 후 등록 메시지를 SQS 로 전송한다.
 * 3. handleBlockAction() — action_schedule_delete 버튼 처리 후 삭제 메시지를 SQS 로 전송한다.
 *
 * ingest 모듈은 TeamMemberService(S3) 를 직접 보유하지 않으므로 한글 이름은
 * SlackApiService.getUserRealName()으로 조회하고, private_metadata 에 userName 을 함께 저장한다.
 */
@Slf4j
public class ScheduleManageFacade {

  static final String CALLBACK_ID = "schedule_submit";
  static final String ACTION_DELETE = "action_schedule_delete";

  private static final ObjectMapper OM = new ObjectMapper();

  private final SlackApiService slackApiService;
  private final WorkerMessageService workerMessageService;
  private final ScheduleMappingQueryService mappingQueryService;

  /**
   * 독립 사용을 위한 기본 생성자.
   */
  public ScheduleManageFacade() {
    this.slackApiService = new SlackApiService();
    this.workerMessageService = WorkerMessageService.getInstance();
    this.mappingQueryService = new ScheduleMappingQueryService();
  }

  /**
   * 공유 SlackApiService 를 주입받는 생성자 (SlackFacade 에서 SlackClient 중복 생성을 방지한다).
   */
  public ScheduleManageFacade(SlackApiService slackApiService) {
    this.slackApiService = slackApiService;
    this.workerMessageService = WorkerMessageService.getInstance();
    this.mappingQueryService = new ScheduleMappingQueryService();
  }

  /**
   * /일정등록 커맨드 수신 처리. 본인 일정이 있으면 등록+삭제 통합 모달, 없으면 등록 전용 모달을 연다.
   * 한글 이름은 Slack users.info 로 real_name 을 조회하여 사용한다.
   */
  public APIGatewayProxyResponseEvent handleCommand(
      String triggerId, String userId, String userName) {
    try {
      log.info("일정등록 커맨드: userId={}, userName={}", userId, userName);

      String koreanName = slackApiService.getUserRealName(userId);
      if (koreanName == null || koreanName.isBlank()) koreanName = userName;

      List<ScheduleMappingQueryService.MappingEntry> mySchedules =
          mappingQueryService.findBySlackUserId(userId);

      if (mySchedules.isEmpty()) {
        slackApiService.openScheduleRegisterOnlyModal(
            triggerId, userName, userId, koreanName);
        log.info("일정등록 전용 모달: userId={}, koreanName={}", userId, koreanName);
      } else {
        slackApiService.openScheduleRegisterAndDeleteModal(
            triggerId, userName, userId, koreanName, mySchedules);
        log.info("일정등록+삭제 통합 모달: userId={}, scheduleCount={}",
            userId, mySchedules.size());
      }

      return HttpResponse.ok("");

    } catch (Exception e) {
      log.error("일정등록 커맨드 처리 오류: userId={}", userId, e);
      return HttpResponse.internalError();
    }
  }

  /**
   * schedule_submit view_submission 처리.
   * 필수값(제목/날짜)과 시간 정합성(과거/종료<=시작)을 검증한 후 SQS 로 등록 메시지를 전송한다.
   */
  public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
    ScheduleModalSubmit modal;
    try {
      modal = ScheduleModalSubmit.parse(body);
      log.info("일정등록 모달 파싱 완료: userId={}, type={}, title={}",
          modal.getUserId(), modal.getType(), modal.getTitle());
    } catch (Exception e) {
      log.warn("일정등록 모달 파싱 실패: {}", e.getMessage(), e);
      return HttpResponse.badRequest("Invalid payload");
    }

    // 필수값 검증.
    if (!modal.hasTitle()) {
      log.info("일정등록 검증 실패 - 제목 없음: userId={}", modal.getUserId());
      return HttpResponse.modalError("block_schedule_title", "제목을 입력해 주세요.");
    }
    if (!modal.hasStartDate()) {
      log.info("일정등록 검증 실패 - 날짜 없음: userId={}", modal.getUserId());
      return HttpResponse.modalError("block_schedule_start_date", "날짜를 선택해 주세요.");
    }

    // 시간 정합성 검증 (과거 시작, 종료<=시작, 과거 종일 일정).
    try {
      String startDt = modal.getStartDateTime();
      String endDt = modal.getEndDateTime();
      ZoneId kst = ZoneId.of("Asia/Seoul");
      DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
      DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

      if (startDt != null && startDt.contains("T")) {
        LocalDateTime startLdt = LocalDateTime.parse(startDt.substring(0, 16), dtFmt);
        LocalDateTime nowKst = LocalDateTime.now(kst);

        if (startLdt.isBefore(nowKst)) {
          log.info("일정등록 검증 실패 - 과거 시작시간: userId={}, start={}", modal.getUserId(), startDt);
          return HttpResponse.modalError("block_schedule_start_time",
              "시작 시간이 현재 시각보다 이전입니다. 미래 시간으로 입력해 주세요.");
        }

        if (endDt != null && endDt.contains("T")) {
          LocalDateTime endLdt = LocalDateTime.parse(endDt.substring(0, 16), dtFmt);
          if (!endLdt.isAfter(startLdt)) {
            log.info("일정등록 검증 실패 - 종료<=시작: userId={}, start={}, end={}", modal.getUserId(), startDt, endDt);
            return HttpResponse.modalError("block_schedule_end_time",
                "종료 시간은 시작 시간보다 늦어야 합니다.");
          }
        }
      } else if (startDt != null) {
        // 종일 일정: 날짜가 오늘 이전인지 확인한다.
        LocalDate startDate = LocalDate.parse(startDt.substring(0, 10), dateFmt);
        LocalDate todayKst = LocalDate.now(kst);
        if (startDate.isBefore(todayKst)) {
          log.info("일정등록 검증 실패 - 과거 날짜: userId={}, date={}", modal.getUserId(), startDt);
          return HttpResponse.modalError("block_schedule_start_date",
              "오늘 이전 날짜는 등록할 수 없습니다.");
        }
      }
    } catch (Exception e) {
      // 입력값 자체가 이상한 경우 검증만 스킵하고 SQS 전송은 진행한다.
      log.warn("일정등록 시간 정합성 검증 중 파싱 오류 (스킵): userId={}, err={}", modal.getUserId(), e.getMessage());
    }

    // 검증 통과 후 SQS 전송.
    try {
      log.info("일정등록 submit: userId={}, koreanName={}, title={}, start={}, end={}",
          modal.getUserId(), modal.getKoreanName(),
          modal.getTitle(), modal.getStartDateTime(), modal.getEndDateTime());

      String messageId = workerMessageService.sendScheduleRegister(
          modal.getUserId(),
          modal.getSlackUserName(),
          modal.getKoreanName(),
          modal.getTitle(),
          modal.getDescription(),
          modal.getStartDateTime(),
          modal.getEndDateTime(),
          modal.getReminderMinutes(),
          modal.getUrl()
      );

      log.info("일정등록 SQS 전송 완료: messageId={}", messageId);
      return HttpResponse.modalResult(true, "일정이 캘린더에 등록됩니다.", "📅 일정등록");

    } catch (AutomationException e) {
      log.error("일정등록 submit 실패 [{}]: userId={}, err={}", e.getErrorCode(), modal.getUserId(), e.getMessage());
      return HttpResponse.modalResult(false, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "📅 일정등록");
    } catch (Exception e) {
      log.error("일정등록 submit 예외: userId={}", modal.getUserId(), e);
      return HttpResponse.modalResult(false, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "📅 일정등록");
    }
  }

  /**
   * action_schedule_delete 버튼(block_actions) 처리.
   * 선택된 calendarEventId 를 SQS 로 보내고 views.update 로 결과 화면을 교체한다.
   */
  public APIGatewayProxyResponseEvent handleBlockAction(String body) {
    String userId = "";
    String viewId = "";
    try {
      String decoded = URLDecoder.decode(
          body.substring("payload=".length()), StandardCharsets.UTF_8);
      JsonNode payload = OM.readTree(decoded);

      userId = payload.path("user").path("id").asText("");
      viewId = payload.path("view").path("id").asText("");

      // 삭제 대상 드롭다운 값은 view.state.values 에서 읽는다.
      String calendarEventId = payload
          .path("view").path("state").path("values")
          .path("block_schedule_select")
          .path("action_schedule_select")
          .path("selected_option").path("value").asText("").trim();

      log.info("일정 삭제 시도: userId={}, calendarEventId={}", userId, calendarEventId);

      if (calendarEventId.isBlank() || "_none_".equals(calendarEventId)) {
        slackApiService.updateScheduleViewWithResult(
            viewId, false, "삭제할 일정을 먼저 선택해 주세요.\n드롭다운에서 삭제할 일정을 선택한 후 삭제 버튼을 눌러 주세요.");
        return HttpResponse.ok("");
      }

      String messageId = workerMessageService.sendScheduleDelete(userId, calendarEventId);
      log.info("일정 삭제 SQS 전송: messageId={}, userId={}, eventId={}",
          messageId, userId, calendarEventId);

      slackApiService.updateScheduleViewWithResult(viewId, true,
          "일정 삭제 요청이 처리됩니다.");
      return HttpResponse.ok("");

    } catch (AutomationException e) {
      log.error("일정 삭제 실패 [{}]: userId={}", e.getErrorCode(), userId);
      tryUpdateView(viewId, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
      return HttpResponse.ok("");
    } catch (Exception e) {
      log.error("일정 삭제 오류: userId={}", userId, e);
      tryUpdateView(viewId, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
      return HttpResponse.ok("");
    }
  }

  /**
   * views.update 호출 시 발생하는 2차 예외를 삼켜서 핸들러 실패로 전파되지 않게 한다.
   */
  private void tryUpdateView(String viewId, String message) {
    if (viewId == null || viewId.isBlank()) return;
    try {
      slackApiService.updateScheduleViewWithResult(viewId, false, message);
    } catch (Exception ex) {
      log.warn("views.update 실패: {}", ex.getMessage());
    }
  }
}
