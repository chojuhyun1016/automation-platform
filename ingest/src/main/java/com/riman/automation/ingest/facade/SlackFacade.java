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
 * Slack 요청 Facade
 *
 * <pre>
 * 책임:
 *   1. Slack 재시도 감지 → 즉시 200
 *   2. 서명 검증
 *   3. Slash Command 분기:
 *      - /재택근무  → 재택 Modal (기존)
 *      - /부재등록  → 부재 Modal (기존)
 *      - /계정관리  → 계정관리 Modal (기존)
 *      - /일정등록  → 일정등록 Modal (기존)
 *      - /현재티켓  → 현재티켓 Modal (신규) ← 추가
 *   4. Modal Submit 분기 (callback_id 기반):
 *      - remote_work_submit    → 재택 SQS (기존)
 *      - absence_submit        → 부재 SQS (기존)
 *      - account_manage_submit → 계정관리 처리 (기존)
 *      - schedule_submit       → 일정등록 처리 (기존)
 *      - current_ticket_submit → 현재티켓 처리 (신규) ← 추가
 *   5. Block Actions 분기 (기존):
 *      - action_account_delete → 계정 삭제 처리
 *      - action_schedule_delete → 일정 삭제 처리
 * </pre>
 *
 * <p><b>현재티켓 의존성:</b>
 * {@link CurrentTicketFacade}는 Google Calendar 직접 조회가 필요하므로
 * 생성자에서 {@link GoogleCalendarClient} 초기화 후 주입한다.
 * Google 인증 키는 S3에서 로드 ({@code GOOGLE_CALENDAR_CREDENTIALS_BUCKET} /
 * {@code GOOGLE_CALENDAR_CREDENTIALS_KEY} 환경변수 필요).
 * 환경변수 미설정 시 currentTicketFacade = null — /현재티켓 비활성 처리.
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
     * 현재티켓 Facade — SlackClient만 있으면 항상 생성 가능.
     * GoogleCalendarClient는 모달 제출 시점에 lazy 초기화.
     */
    private final CurrentTicketFacade currentTicketFacade;

    public SlackFacade() {
        this.verifier = new SlackSignatureVerifier();

        // ── 병렬 초기화 ────────────────────────────────────────────────────────
        // ForkJoinPool.commonPool() 은 Lambda 환경에서 스레드 수가 제한되어
        // 같은 스레드가 재사용되며 순차 실행될 수 있음.
        // 전용 고정 스레드풀(5개)을 명시적으로 생성해 확실한 병렬 실행 보장.
        //
        // 초기화 대상 (서로 의존성 없음 → 완전 병렬):
        //   Thread-1: WorkerMessageService (SQS 클라이언트)    ~661ms
        //   Thread-2: SlackApiService (HTTP 커넥션 풀)          ~904ms
        //   Thread-3: AccountManageFacade (KMS)               ~268ms
        //   Thread-4: ScheduleManageFacade (DynamoDB pre-warm) ~747ms
        //   Thread-5: CurrentTicketFacade                        ~5ms
        //
        // 병렬 후 예상: max(661, 904, 268, 747, 5) = ~904ms
        // 기존 순차 합계 ~2585ms 대비 약 1680ms 단축

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CompletableFuture<Void> workerFuture = CompletableFuture
                    .runAsync(WorkerMessageService::getInstance, pool);

            CompletableFuture<SlackApiService> slackFuture = CompletableFuture
                    .supplyAsync(SlackApiService::new, pool);

            // SlackApiService가 완료된 후에 의존 Facade 3개를 전용풀에서 병렬 생성
            SlackApiService svc = slackFuture.join(); // SlackApiService 먼저 완료 대기

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
            pool.shutdown(); // 초기화 완료 후 풀 반납 (스레드 리소스 누수 방지)
        }

        log.info("SlackFacade initialized (currentTicket=활성)");
    }

    public APIGatewayProxyResponseEvent handle(
            Map<String, String> headers, String body, String path) {

        // 1. 재시도 감지
        if (isSlackRetry(headers)) {
            log.warn("Slack 재시도 감지 → 200 반환: path={}, retryNum={}",
                    path, getHeader(headers, RETRY_NUM_HEADER));
            return HttpResponse.ok("");
        }

        // 2. 서명 검증
        if (!verifier.verify(headers, body)) {
            log.warn("Slack 서명 검증 실패: path={}", path);
            return HttpResponse.unauthorized();
        }
        log.info("Slack 서명 검증 성공: path={}", path);

        // 3. 요청 유형 분기
        try {
            if (body.startsWith("payload=")) {
                // block_actions(삭제 버튼 클릭) vs view_submission(모달 제출) 구분
                String payloadType = extractPayloadType(body);
                if ("block_actions".equals(payloadType)) {
                    return handleBlockActions(body);
                }
                return handleModalSubmit(body);
            }
            return handleSlashCommand(body);
        } catch (Exception e) {
            log.error("Slack 요청 처리 실패: path={}", path, e);
            // HttpResponse.internalError() → 500 반환 시 Slack이
            // "{reason} 오류가 발생해 .../부재등록*에 실패했습니다." 템플릿을
            // {reason} 미치환 상태로 노출하는 버그 + Slack이 재시도(Retry)를 반복
            // → 200 반환으로 차단
            return HttpResponse.ok("");
        }
    }

    // =========================================================================
    // Slash Command
    // =========================================================================

    private APIGatewayProxyResponseEvent handleSlashCommand(String body) throws Exception {
        SlackCommandRequest cmd = SlackCommandRequest.parse(body);
        log.info("Slash command: [{}], user={}", cmd.getCommand(), cmd.getUserName());

        // /재택근무 (기존 — 변경 없음)
        if (cmd.isRemoteWorkCommand()) {
            slackApiService.openRemoteWorkModal(
                    cmd.getTriggerId(), cmd.getUserName(), cmd.getUserId());
            log.info("재택 Modal 열기 완료: user={}", cmd.getUserName());
            return HttpResponse.ok("");
        }

        // /부재등록 (기존 — 변경 없음)
        if (cmd.isAbsenceCommand()) {
            try {
                slackApiService.openAbsenceModal(
                        cmd.getTriggerId(), cmd.getUserName(), cmd.getUserId());
                log.info("부재 Modal 열기 완료: user={}", cmd.getUserName());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("expired_trigger_id")) {
                    // 콜드스타트로 인한 trigger_id 만료 — 200 반환으로 Slack 에러 차단
                    // 사용자는 다시 입력하면 바로 성공 (Lambda가 이미 warm 상태)
                    log.warn("부재 Modal 열기 실패 (expired_trigger_id, Lambda 초기화 지연): user={}",
                            cmd.getUserName());
                } else {
                    log.error("부재 Modal 열기 실패: user={}", cmd.getUserName(), e);
                }
            }
            return HttpResponse.ok("");
        }

        // /계정관리 (기존 — 변경 없음)
        if (cmd.isAccountManageCommand()) {
            return accountManageFacade.handleCommand(
                    cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
        }

        // /일정등록 (기존 — 변경 없음)
        if (cmd.isScheduleCommand()) {
            return scheduleManageFacade.handleCommand(
                    cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
        }

        // /현재티켓 (신규) ← 추가
        if (cmd.isCurrentTicketCommand()) {
            return currentTicketFacade.handleCommand(
                    cmd.getTriggerId(), cmd.getUserId(), cmd.getUserName());
        }

        log.warn("등록되지 않은 커맨드: [{}]", cmd.getCommand());
        return HttpResponse.ok("");
    }

    // =========================================================================
    // Modal Submit
    // =========================================================================

    private APIGatewayProxyResponseEvent handleModalSubmit(String body) throws Exception {
        String callbackId = extractCallbackId(body);
        log.info("Modal submit: callbackId={}", callbackId);

        // 계정관리 (기존 — 변경 없음)
        if (CALLBACK_ACCOUNT.equals(callbackId)) {
            return accountManageFacade.handleModalSubmit(body);
        }

        // 일정등록 (기존 — 변경 없음)
        if (CALLBACK_SCHEDULE.equals(callbackId)) {
            return scheduleManageFacade.handleModalSubmit(body);
        }

        // 현재티켓 (신규) ← 추가
        if (CALLBACK_CURRENT_TICKET.equals(callbackId)) {
            return currentTicketFacade.handleModalSubmit(body);
        }

        // 부재등록 (기존 — 변경 없음)
        if (CALLBACK_ABSENCE.equals(callbackId)) {
            return handleAbsenceSubmit(body);
        }

        // 기본: 재택근무 (기존 — 변경 없음)
        return handleRemoteWorkSubmit(body);
    }

    // =========================================================================
    // Block Actions — 삭제 버튼 클릭 (기존 — 변경 없음)
    // =========================================================================

    /**
     * block_actions 이벤트 처리
     *
     * <p>계정관리 모달의 삭제 버튼(action_account_delete) 클릭 시 호출.
     * Slack confirm 다이얼로그 승인 후 이 핸들러로 진입한다.
     * 모달 state에서 입력된 ID/비밀번호를 읽어 일치 확인 후 삭제.
     * block_actions 응답은 항상 200.
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

    // ── 재택근무 ────────────────────────────────────────────────────────────

    /**
     * 재택근무 모달 제출 처리
     *
     * <p><b>SQS 비동기 전송:</b>
     * SQS sendMessage()는 네트워크 상태에 따라 100ms~2초 소요될 수 있어
     * Slack view_submission 응답 제한을 초과할 위험이 있다.
     * Worker가 SQS를 소비해 Slack DM으로 결과를 전송하므로,
     * 여기서는 유효성 검증 후 SQS 전송만 Thread로 수행하고 즉시 응답한다.
     *
     * <p>Lambda는 handleRequest() 반환 시 컨테이너를 freeze하므로
     * Thread.join()으로 SQS 전송 완료를 보장한다.
     * (CurrentTicketFacade.handleModalSubmit 과 동일 패턴)
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

    // ── 부재등록 ──────────────────────────────────────────────────────────

    /**
     * 부재등록 모달 제출 처리
     *
     * <p><b>SQS 비동기 전송:</b>
     * SQS sendMessage()는 네트워크 상태에 따라 100ms~2초 소요될 수 있어
     * Slack view_submission 응답 제한을 초과할 위험이 있다.
     * Worker가 SQS를 소비해 부재 등록 + Slack DM 결과를 전송하므로,
     * 여기서는 유효성 검증 후 SQS 전송만 Thread로 수행하고 즉시 응답한다.
     *
     * <p>Lambda는 handleRequest() 반환 시 컨테이너를 freeze하므로
     * Thread.join()으로 SQS 전송 완료를 보장한다.
     * (CurrentTicketFacade.handleModalSubmit 과 동일 패턴)
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

        // 유효성 검증
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

    // =========================================================================
    // 내부
    // =========================================================================

    /**
     * payload body에서 callback_id 빠르게 추출 (모달 종류 사전 판별용)
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
     * payload body에서 최상위 type 필드 추출 (block_actions vs view_submission 판별용)
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
     * Slack 재시도 여부 판단.
     *
     * <p><b>버그 수정:</b> 기존에는 X-Slack-Retry-Num 헤더 존재 여부만 확인했으나
     * view_submission(모달 제출)에도 이 헤더가 붙어 오는 경우가 있어
     * 정상 요청을 재시도로 잘못 판단 → 무시 → DM이 오지 않는 현상의 근본 원인.
     *
     * <p><b>수정 기준 (Slack 공식):</b>
     * X-Slack-Retry-Reason = "http_timeout" 인 경우만 진짜 타임아웃 재시도.
     * Retry-Num이 있어도 Reason이 http_timeout이 아니면 정상 처리한다.
     */
    private boolean isSlackRetry(Map<String, String> headers) {
        String retryNum = getHeader(headers, RETRY_NUM_HEADER);
        if (retryNum == null) return false;
        String retryReason = getHeader(headers, RETRY_REASON_HEADER);
        return "http_timeout".equals(retryReason);
    }

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
