package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.ingest.dto.slack.AbsenceModalSubmit;
import com.riman.automation.ingest.dto.slack.RemoteWorkModalSubmit;
import com.riman.automation.ingest.dto.slack.SlackCommandRequest;
import com.riman.automation.ingest.security.SlackSignatureVerifier;
import com.riman.automation.ingest.service.SlackApiService;
import com.riman.automation.ingest.service.WorkerMessageService;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slack 요청 전체를 수신하는 Facade (재시도 감지, 서명 검증, 커맨드/모달 라우팅).
 *
 * 분기 규칙:
 * - Slash Command → /재택근무, /부재등록, /계정관리, /일정등록, /현재티켓
 * - Modal Submit  → callback_id 기반 (remote_work_submit, absence_submit,
 *   account_manage_submit, schedule_submit, current_ticket_submit)
 * - Block Actions → action_id 기반 (action_account_delete, action_schedule_delete)
 *
 * CurrentTicketFacade 는 {@link GoogleCalendarClient} 가 필요하나 실제 초기화는
 * 모달 제출 시점으로 지연된다. Google 인증 키는 S3 (환경변수
 * GOOGLE_CALENDAR_CREDENTIALS_BUCKET / GOOGLE_CALENDAR_CREDENTIALS_KEY)에서 로드한다.
 */
@Slf4j
public class SlackFacade {

  private static final String RETRY_NUM_HEADER = "X-Slack-Retry-Num";
  private static final String RETRY_REASON_HEADER = "X-Slack-Retry-Reason";

  private static final String CALLBACK_REMOTE_WORK = "remote_work_submit";
  private static final String CALLBACK_ABSENCE = "absence_submit";
  private static final String CALLBACK_ACCOUNT = "account_manage_submit";
  private static final String CALLBACK_SCHEDULE = "schedule_submit";
  private static final String CALLBACK_CURRENT_TICKET = "current_ticket_submit";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final SlackSignatureVerifier verifier;
  private final SlackApiService slackApiService;
  private final WorkerMessageService workerMessageService = WorkerMessageService.getInstance();
  private final AccountManageFacade accountManageFacade;
  private final ScheduleManageFacade scheduleManageFacade;

  /**
   * CurrentTicket Facade. SlackClient 만으로 항상 생성 가능하며 GoogleCalendarClient 는 lazy 초기화된다.
   */
  private final CurrentTicketFacade currentTicketFacade;

  /**
   * 의존 Facade/Service 를 병렬로 초기화한다.
   *
   * ForkJoinPool.commonPool() 은 Lambda 환경에서 스레드 수가 제한되어 같은 스레드가 재사용되며
   * 순차 실행될 수 있으므로, 확실한 병렬성을 위해 고정 5개 풀을 명시적으로 생성한다.
   * 초기화 비용은 WorkerMessageService ~661ms, SlackApiService ~904ms, AccountManageFacade ~268ms,
   * ScheduleManageFacade ~747ms (DynamoDB pre-warm 포함), CurrentTicketFacade ~5ms 이며
   * 병렬화 시 최댓값(~904ms)으로 수렴한다. 순차 합계 ~2585ms 대비 약 1680ms 를 단축한다.
   */
  public SlackFacade() {
    this.verifier = new SlackSignatureVerifier();

    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      CompletableFuture<Void> workerFuture = CompletableFuture
          .runAsync(WorkerMessageService::getInstance, pool);

      CompletableFuture<SlackApiService> slackFuture = CompletableFuture
          .supplyAsync(SlackApiService::new, pool);

      // SlackApiService 완료 후 이를 공유하는 세 Facade 를 병렬 생성한다.
      SlackApiService svc = slackFuture.join();

      CompletableFuture<AccountManageFacade> accountFuture = CompletableFuture
          .supplyAsync(() -> new AccountManageFacade(svc), pool);

      CompletableFuture<ScheduleManageFacade> scheduleFuture = CompletableFuture
          .supplyAsync(() -> new ScheduleManageFacade(svc), pool);

      CompletableFuture<CurrentTicketFacade> ticketFuture = CompletableFuture
          .supplyAsync(() -> new CurrentTicketFacade(svc.getSlackClient()), pool);

      workerFuture.join();
      this.slackApiService = svc;
      this.accountManageFacade = accountFuture.join();
      this.scheduleManageFacade = scheduleFuture.join();
      this.currentTicketFacade = ticketFuture.join();

    } finally {
      // 초기화 완료 후 풀을 반납하여 스레드 리소스 누수를 방지한다.
      pool.shutdown();
    }

    log.info("SlackFacade initialized (currentTicket=활성)");
  }

  /**
   * Slack 요청 전체 라우팅 진입점.
   * 재시도 감지 → 서명 검증 → payload 유형(block_actions/view_submission/slash command) 분기 순서로 처리한다.
   * 예외 발생 시에도 반드시 200 을 반환하여 Slack 재시도 루프를 차단한다.
   */
  public APIGatewayProxyResponseEvent handle(
      Map<String, String> headers, String body, String path) {

    if (isSlackRetry(headers)) {
      log.warn("Slack 재시도 감지 → 200 반환: path={}, retryNum={}",
          path, getHeader(headers, RETRY_NUM_HEADER));
      return HttpResponse.ok("");
    }

    if (!verifier.verify(headers, body)) {
      log.warn("Slack 서명 검증 실패: path={}", path);
      return HttpResponse.unauthorized();
    }
    log.info("Slack 서명 검증 성공: path={}", path);

    try {
      if (body.startsWith("payload=")) {
        // block_actions(버튼 클릭)와 view_submission(모달 제출)을 구분해서 처리한다.
        String payloadType = extractPayloadType(body);
        if ("block_actions".equals(payloadType)) {
          return handleBlockActions(body);
        }
        return handleModalSubmit(body);
      }
      return handleSlashCommand(body);
    } catch (Exception e) {
      log.error("Slack 요청 처리 실패: path={}", path, e);
      // 500 반환 시 Slack 이 "{reason} 오류가 발생해 .../부재등록*에 실패했습니다." 템플릿을
      // {reason} 미치환 상태로 노출하는 버그 + Slack 재시도 반복이 발생하므로 200 으로 차단한다.
      return HttpResponse.ok("");
    }
  }

  /**
   * Slash Command 분기. 커맨드별 Facade/Service 로 위임한다.
   */
  private APIGatewayProxyResponseEvent handleSlashCommand(String body) throws Exception {
    SlackCommandRequest cmd = SlackCommandRequest.parse(body);
    log.info("Slash command: [{}], user={}", cmd.getCommand(), cmd.getUserName());

    if (cmd.isRemoteWorkCommand()) {
      slackApiService.openRemoteWorkModal(
          cmd.getTriggerId(), cmd.getUserName(), cmd.getUserId());
      log.info("재택 Modal 열기 완료: user={}", cmd.getUserName());
      return HttpResponse.ok("");
    }

    if (cmd.isAbsenceCommand()) {
      try {
        slackApiService.openAbsenceModal(
            cmd.getTriggerId(), cmd.getUserName(), cmd.getUserId());
        log.info("부재 Modal 열기 완료: user={}", cmd.getUserName());
      } catch (Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("expired_trigger_id")) {
          // 콜드스타트로 trigger_id 가 만료된 케이스. 사용자가 재입력하면 warm 상태로 즉시 성공하므로
          // Slack 에러 메시지 노출을 막기 위해 200 을 반환한다.
          log.warn("부재 Modal 열기 실패 (expired_trigger_id, Lambda 초기화 지연): user={}",
              cmd.getUserName());
        } else {
          log.error("부재 Modal 열기 실패: user={}", cmd.getUserName(), e);
        }
      }
      return HttpResponse.ok("");
    }

    if (cmd.isAccountManageCommand()) {
      return accountManageFacade.handleCommand(
          cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
    }

    if (cmd.isScheduleCommand()) {
      return scheduleManageFacade.handleCommand(
          cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
    }

    if (cmd.isCurrentTicketCommand()) {
      return currentTicketFacade.handleCommand(
          cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
    }

    log.warn("등록되지 않은 커맨드: [{}]", cmd.getCommand());
    return HttpResponse.ok("");
  }

  /**
   * view_submission 분기. callback_id 를 기준으로 담당 Facade 로 위임하며
   * 알 수 없는 callback_id 는 재택근무 모달로 폴백한다.
   */
  private APIGatewayProxyResponseEvent handleModalSubmit(String body) throws Exception {
    String callbackId = extractCallbackId(body);
    log.info("Modal submit: callbackId={}", callbackId);

    if (CALLBACK_ACCOUNT.equals(callbackId)) {
      return accountManageFacade.handleModalSubmit(body);
    }

    if (CALLBACK_SCHEDULE.equals(callbackId)) {
      return scheduleManageFacade.handleModalSubmit(body);
    }

    if (CALLBACK_CURRENT_TICKET.equals(callbackId)) {
      return currentTicketFacade.handleModalSubmit(body);
    }

    if (CALLBACK_ABSENCE.equals(callbackId)) {
      return handleAbsenceSubmit(body);
    }

    return handleRemoteWorkSubmit(body);
  }

  /**
   * block_actions 분기. 계정/일정 모달의 삭제 버튼을 각각 처리한다.
   * Slack confirm 다이얼로그 승인 후 진입되며 응답은 항상 200 이다.
   */
  private APIGatewayProxyResponseEvent handleBlockActions(String body) throws Exception {
    String decoded = URLDecoder.decode(body.substring("payload=".length()), StandardCharsets.UTF_8);
    JsonNode payload = OBJECT_MAPPER.readTree(decoded);
    String actionId = payload.path("actions").path(0).path("action_id").asText("");
    log.info("Block actions: actionId={}", actionId);

    if ("action_account_delete".equals(actionId)) {
      return accountManageFacade.handleBlockAction(body);
    }

    if ("action_schedule_delete".equals(actionId)) {
      return scheduleManageFacade.handleBlockAction(body);
    }

    return HttpResponse.ok("");
  }

  /**
   * 재택근무 모달 제출 처리.
   *
   * SQS sendMessage() 는 네트워크 상황에 따라 100ms~2초가 소요될 수 있어 Slack view_submission
   * 3초 제한을 초과할 수 있다. Worker 가 실제 처리 + Slack DM 을 담당하므로 여기서는 유효성
   * 검증 후 SQS 전송만 Thread 로 수행하고 join() 으로 전송 완료를 보장한 뒤 즉시 200 을 반환한다.
   * Lambda 는 handleRequest() 반환 시 컨테이너를 freeze 하므로 join() 이 필수다.
   */
  private APIGatewayProxyResponseEvent handleRemoteWorkSubmit(String body) throws Exception {
    RemoteWorkModalSubmit modal = RemoteWorkModalSubmit.parse(body);

    if (!modal.isViewSubmission()) {
      log.info("view_submission 아님, 무시: type={}", modal.getType());
      return HttpResponse.ok("");
    }

    log.info("재택 submit: user={}, date={}, action={}",
        modal.getUserName(), modal.getDate(), modal.getAction());

    if (modal.getDate().isEmpty()) {
      return HttpResponse.modalError("action_date", "날짜를 선택해주세요.");
    }
    if (!modal.isValidAction()) {
      return HttpResponse.modalError("action_type", "신청 또는 취소를 선택해주세요.");
    }

    Thread sqsThread = new Thread(() -> {
      try {
        String messageId = workerMessageService.sendRemoteWork(
            modal.getUserId(), modal.getUserName(),
            modal.getDate(), modal.getAction());
        log.info("재택 SQS 전송 완료: messageId={}, user={}, date={}, action={}",
            messageId, modal.getUserName(), modal.getDate(), modal.getAction());
      } catch (Exception e) {
        log.error("재택 SQS 전송 실패: user={}, date={}, action={}",
            modal.getUserName(), modal.getDate(), modal.getAction(), e);
      }
    }, "remote-work-sqs-sender");
    sqsThread.start();

    try {
      sqsThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("재택 SQS 전송 스레드 인터럽트: user={}", modal.getUserName());
    }

    return HttpResponse.ok("");
  }

  /**
   * 부재등록 모달 제출 처리.
   * 재택근무 submit 과 동일한 SQS 위임 + join() 패턴으로 Lambda freeze 전에 전송을 보장한다.
   */
  private APIGatewayProxyResponseEvent handleAbsenceSubmit(String body) throws Exception {
    AbsenceModalSubmit modal = AbsenceModalSubmit.parse(body);

    if (!modal.isViewSubmission()) {
      log.info("view_submission 아님, 무시: type={}", modal.getType());
      return HttpResponse.ok("");
    }

    log.info("부재 submit: user={}, type={}, action={}, start={}, end={}, reason={}",
        modal.getUserName(), modal.getAbsenceType(), modal.getAction(),
        modal.getStartDate(), modal.getEndDate(), modal.getReason());

    if (!modal.isValidAbsenceType()) {
      return HttpResponse.modalError("action_absence_type", "부재 유형을 선택해주세요.");
    }
    if (!modal.isValidAction()) {
      return HttpResponse.modalError("action_action_type", "등록 또는 취소를 선택해주세요.");
    }
    if (modal.getStartDate().isEmpty()) {
      return HttpResponse.modalError("action_start_date", "시작일을 선택해주세요.");
    }

    Thread sqsThread = new Thread(() -> {
      try {
        String messageId = workerMessageService.sendAbsence(
            modal.getUserId(), modal.getUserName(),
            modal.getAbsenceType(), modal.getAction(),
            modal.getStartDate(), modal.getEndDate(),
            modal.getReason());
        log.info("부재 SQS 전송 완료: messageId={}, user={}, type={}, action={}",
            messageId, modal.getUserName(), modal.getAbsenceType(), modal.getAction());
      } catch (Exception e) {
        log.error("부재 SQS 전송 실패: user={}, type={}, action={}",
            modal.getUserName(), modal.getAbsenceType(), modal.getAction(), e);
      }
    }, "absence-sqs-sender");
    sqsThread.start();

    try {
      sqsThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("부재 SQS 전송 스레드 인터럽트: user={}", modal.getUserName());
    }

    return HttpResponse.ok("");
  }

  /**
   * payload body 에서 callback_id 만 빠르게 추출하여 모달 종류를 사전 판별한다.
   * 파싱 실패 시 재택 모달로 폴백한다.
   */
  private String extractCallbackId(String body) {
    try {
      String decoded = URLDecoder.decode(
          body.substring("payload=".length()), StandardCharsets.UTF_8);
      JsonNode node = OBJECT_MAPPER.readTree(decoded);
      return node.path("view").path("callback_id").asText("");
    } catch (Exception e) {
      log.warn("callback_id 추출 실패, 재택 모달로 폴백", e);
      return CALLBACK_REMOTE_WORK;
    }
  }

  /**
   * payload body 의 최상위 type 필드를 추출하여 block_actions/view_submission 을 판별한다.
   */
  private String extractPayloadType(String body) {
    try {
      String decoded = URLDecoder.decode(
          body.substring("payload=".length()), StandardCharsets.UTF_8);
      return OBJECT_MAPPER.readTree(decoded).path("type").asText("");
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Slack 재시도 여부를 판정한다.
   *
   * 과거에는 X-Slack-Retry-Num 헤더 존재 여부만 확인했으나 view_submission 에도 이 헤더가
   * 붙어 오는 경우가 있어 정상 요청을 재시도로 잘못 판단하여 DM 이 오지 않는 문제가 있었다.
   * Slack 공식 기준: X-Slack-Retry-Reason = "http_timeout" 인 경우만 재시도로 처리한다.
   * Retry-Num 헤더가 존재해도 Reason 이 http_timeout 이 아니면 정상 요청으로 처리한다.
   */
  private boolean isSlackRetry(Map<String, String> headers) {
    String retryNum = getHeader(headers, RETRY_NUM_HEADER);
    if (retryNum == null) return false;
    String retryReason = getHeader(headers, RETRY_REASON_HEADER);
    return "http_timeout".equals(retryReason);
  }

  /**
   * 헤더를 대소문자 구분 없이 조회한다.
   */
  private String getHeader(Map<String, String> headers, String name) {
    if (headers == null) return null;
    if (headers.containsKey(name)) return headers.get(name);
    String lower = name.toLowerCase();
    return headers.entrySet().stream()
        .filter(e -> e.getKey().toLowerCase().equals(lower))
        .map(Map.Entry::getValue)
        .findFirst().orElse(null);
  }
}
