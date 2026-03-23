package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.code.DueDateUrgencyCode;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.exception.AutomationException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.ingest.dto.slack.CurrentTicketModalSubmit;
import com.riman.automation.ingest.payload.CurrentTicketModalBuilder;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * /현재티켓 커맨드 처리 Facade
 *
 * <p><b>두 가지 진입점:</b>
 * <ol>
 *   <li>{@link #handleCommand}     — /현재티켓 슬래시커맨드 → 기간 선택 모달 열기</li>
 *   <li>{@link #handleModalSubmit} — current_ticket_submit → 티켓 조회 → DM 전송</li>
 * </ol>
 *
 * <p><b>설계 원칙 — 모달 오픈과 캘린더 초기화 분리:</b>
 * 모달을 여는 것은 SlackClient(SLACK_BOT_TOKEN)만 있으면 되므로 항상 가능하다.
 * GoogleCalendarClient는 모달 제출 후 티켓을 실제 조회할 때만 필요하며,
 * 이 시점에 lazy하게 초기화한다.
 * 이를 통해 GOOGLE_CALENDAR_CREDENTIALS_BUCKET 환경변수가 없어도
 * 모달은 정상 표시되고, 조회 시점에 오류 메시지를 DM으로 안내할 수 있다.
 *
 * <p><b>조회 기준:</b>
 * <ul>
 *   <li>담당자: 요청자 본인 (Slack real_name → 캘린더 이벤트 제목의 이름 매칭)</li>
 *   <li>상태: 미완료(DONE 아님)</li>
 *   <li>분기: 현재 분기(Q1~Q4) 전체 범위</li>
 *   <li>기간 드롭다운: 일별(오늘) / 주별(이번 주 월~일) / 분기별(분기 전체)</li>
 * </ul>
 *
 * <p><b>환경변수:</b>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN}           — Slack Bot Token (모달 오픈 + DM 전송)</li>
 *   <li>{@code TICKET_CALENDAR_ID}         — 티켓 조회용 Google Calendar ID (조회 시 필요)</li>
 *   <li>{@code JIRA_BASE_URL}              — Jira 링크 생성용 Base URL</li>
 *   <li>{@code GOOGLE_CALENDAR_CREDENTIALS_BUCKET} — Google 인증 키 S3 버킷 (조회 시 필요)</li>
 *   <li>{@code GOOGLE_CALENDAR_CREDENTIALS_KEY}    — Google 인증 키 S3 경로 (기본값: google-credentials.json)</li>
 * </ul>
 */
@Slf4j
public class CurrentTicketFacade {

    static final String SLASH_COMMAND = "/현재티켓";
    static final String CALLBACK_ID = "current_ticket_submit";

    // ── 이슈 키 패턴 (DailyCalendarTicketCollector와 동일) ──────────────────
    private static final Pattern ISSUE_KEY_PATTERN =
            Pattern.compile("\\[Jira\\]\\s+([A-Z]+-\\d+)|\\[([A-Z]+-\\d+)\\]");

    // ── 담당자 이름 패턴 (DailyCalendarTicketCollector와 동일) ───────────────
    private static final Pattern ASSIGNEE_PATTERN =
            Pattern.compile("\\(([^)]+)\\)\\s*$");

    private static final ObjectMapper OM = new ObjectMapper();

    private static final String INDENT = "\u3000\u3000";
    private static final String INDENT2 = "\u3000\u3000\u3000";

    // ── 캐시 (Lambda 컨테이너 재사용 간 유지) ──────────────────────────────
    // Lambda 컨테이너는 warm 상태에서 재사용되므로 static 필드로 캐시하면
    // S3Client 생성(~300ms×2), 인증 키 로드(~600ms), 팀원 정보 로드(~100ms) 절약.
    private static volatile S3Client cachedS3Client;
    private static volatile GoogleCalendarClient cachedCalendarClient;
    private static volatile Map<String, String> cachedTeamMemberMap;

    private final SlackClient slackClient;
    private final String jiraBaseUrl;
    private final String ticketCalendarId;
    // team-members.json에서 slackUserId → name 조회에 사용
    private final String configBucket;
    private final String teamMembersKey;

    /**
     * SlackFacade 생성자에서 주입.
     *
     * <p>GoogleCalendarClient는 여기서 받지 않는다.
     * 모달 오픈에는 Calendar가 불필요하므로, 조회 시점에 lazy 초기화한다.
     * 담당자 매칭은 team-members.json에서 slackUserId → name으로 정확히 조회한다.
     *
     * @param slackClient Slack Bot Token으로 생성된 클라이언트
     */
    public CurrentTicketFacade(SlackClient slackClient) {
        this.slackClient = slackClient;
        this.jiraBaseUrl = System.getenv("JIRA_BASE_URL") != null
                ? System.getenv("JIRA_BASE_URL") : "https://riman-it.atlassian.net";
        this.ticketCalendarId = System.getenv("TICKET_CALENDAR_ID");
        this.configBucket = System.getenv("CONFIG_BUCKET");
        String tmKey = System.getenv("TEAM_MEMBERS_KEY");
        this.teamMembersKey = (tmKey != null && !tmKey.isBlank()) ? tmKey : "team-members.json";
    }

    // =========================================================================
    // 1. 커맨드 — /현재티켓
    // =========================================================================

    /**
     * /현재티켓 슬래시커맨드 처리.
     *
     * <p>기간 선택 모달(일별/주별/분기별)을 열고 200 반환.
     * Calendar 초기화 여부와 무관하게 항상 모달을 표시한다.
     */
    public APIGatewayProxyResponseEvent handleCommand(
            String triggerId, String userId, String userName) {
        try {
            log.info("현재티켓 커맨드: userId={}, userName={}", userId, userName);
            String payload = CurrentTicketModalBuilder.build(triggerId, userId);
            slackClient.openView(payload);
            log.info("현재티켓 모달 열기 완료: userId={}", userId);

            // ── 사전 초기화 ────────────────────────────────────────────────
            // 사용자가 모달에서 기간을 선택하는 동안(1~3초) 캘린더 클라이언트를
            // 미리 생성. handleCommand() return → Lambda freeze 후 다음
            // invocation(modal submit)에서 thaw되면 초기화 완료 상태가 됨.
            // 이를 통해 modal submit 시 Calendar API + DM 전송만 수행 (~2.5초).
            if (cachedCalendarClient == null) {
                Thread preWarm = new Thread(() -> {
                    try {
                        getOrCreateCalendarClient();
                    } catch (Exception e) {
                        log.debug("현재티켓 캘린더 사전 초기화 실패 (submit 시점에 재시도): {}", e.getMessage());
                    }
                }, "current-ticket-prewarm");
                preWarm.setDaemon(true);
                preWarm.start();
            }

            return HttpResponse.ok("");
        } catch (AutomationException e) {
            log.error("현재티켓 커맨드 처리 실패 [{}]: userId={}, cause={}",
                    e.getErrorCode(), userId, e.getMessage());
            return HttpResponse.internalError();
        } catch (Exception e) {
            log.error("현재티켓 커맨드 처리 중 예기치 않은 오류: userId={}", userId, e);
            return HttpResponse.internalError();
        }
    }

    // =========================================================================
    // 2. Modal Submit — current_ticket_submit
    // =========================================================================

    /**
     * 기간 선택 모달 제출 처리.
     *
     * <p><b>Lambda 응답 타이밍:</b>
     * Lambda Runtime은 handleRequest()가 return된 후에야 HTTP 응답을 전송한다.
     * 따라서 Thread.join()으로 완료를 기다리면 그 시간만큼 Slack 응답이 지연된다.
     *
     * <p><b>해결 방법 — 정적 캐시 + 사전 초기화 + join(timeout):</b>
     * <ol>
     *   <li>S3Client, GoogleCalendarClient, team-members.json을 static 캐시하여
     *       warm 상태에서 ~1,200ms 절약</li>
     *   <li>handleCommand() 시점에 사전 초기화 스레드를 시작하여
     *       사용자 모달 상호작용 시간(1~3초) 동안 캐시 준비</li>
     *   <li>join(2500)으로 최대 2.5초 대기 → Slack 3초 제한 이내 응답 보장</li>
     *   <li>타임아웃 발생 시 DM은 다음 Lambda invocation에서 스레드 재개 시 전송</li>
     * </ol>
     *
     * <p><b>warm 상태 예상 소요:</b> Calendar API(~2s) + DM(~0.5s) = ~2.5s
     */
    public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
        CurrentTicketModalSubmit modal;
        try {
            modal = CurrentTicketModalSubmit.parse(body);
        } catch (Exception e) {
            log.warn("현재티켓 모달 파싱 실패: {}", e.getMessage());
            return HttpResponse.badRequest("Invalid payload");
        }

        if (!modal.isViewSubmission()) {
            return HttpResponse.ok("");
        }

        final String userId = modal.getUserId();
        final String period = modal.getPeriod();
        log.info("현재티켓 조회 요청 수신: userId={}, period={}", userId, period);

        // ── Thread 시작 + join(timeout) ──────────────────────────────────────
        // Lambda Runtime은 handleRequest() return 후에야 HTTP 응답을 전송하므로
        // join(2500)으로 최대 대기 시간을 제한하여 Slack 3초 응답 제한을 준수.
        // 사전 초기화 + 캐시로 대부분 2.5초 이내 완료됨.
        // 타임아웃 시 스레드는 freeze 후 다음 invocation에서 재개(best-effort).
        Thread worker = new Thread(() -> {
            try {
                log.info("현재티켓 DM 전송 시작: userId={}, period={}", userId, period);
                sendTicketDm(userId, period);
            } catch (Exception e) {
                log.error("현재티켓 DM 전송 실패: userId={}, period={}", userId, period, e);
            }
        }, "current-ticket-worker");
        worker.start();

        try {
            worker.join(2500);
            if (worker.isAlive()) {
                log.warn("현재티켓 worker 2.5초 초과 — Slack 응답 우선 반환: userId={}", userId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("현재티켓 worker Thread 인터럽트: userId={}", userId);
        }

        // view_submission에 대한 정상 응답 — 모달 닫기
        // response_action=clear 또는 빈 200 모두 Slack이 모달을 닫음
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(java.util.Map.of("Content-Type", "application/json"))
                .withBody("{\"response_action\":\"clear\"}");
    }

    // =========================================================================
    // 내부 — 티켓 수집 + DM 전송
    // =========================================================================

    /**
     * 기간에 맞는 캘린더 범위를 계산하고 티켓을 수집하여 DM으로 전송한다.
     *
     * <p>조회 범위:
     * <ul>
     *   <li>일별   : 오늘 하루</li>
     *   <li>주별   : 이번 주 월요일 ~ 일요일</li>
     *   <li>분기별  : 현재 분기 전체 (Q1~Q4 start ~ end)</li>
     * </ul>
     *
     * <p>공통 필터: 담당자 = 요청자 본인, 상태 = 미완료(DONE 아님)
     */
    private void sendTicketDm(String userId, String period) {
        if (ticketCalendarId == null || ticketCalendarId.isBlank()) {
            log.warn("TICKET_CALENDAR_ID 환경변수 미설정 — 현재티켓 조회 불가: userId={}", userId);
            sendErrorDm(userId, "⚠️ 티켓 캘린더 ID가 설정되지 않아 조회할 수 없습니다.\n관리자에게 문의해 주세요.");
            return;
        }

        // ── GoogleCalendarClient (캐시 또는 lazy 초기화) ─────────────────────
        GoogleCalendarClient calendarClient;
        try {
            calendarClient = getOrCreateCalendarClient();
        } catch (Exception e) {
            log.error("GoogleCalendarClient 초기화 실패: userId={}, cause={}", userId, e.getMessage());
            sendErrorDm(userId, "⚠️ 캘린더 연결에 실패했습니다. 관리자에게 문의해 주세요.");
            return;
        }

        // ── 요청자 이름 조회 (team-members.json에서 slackUserId → name) ──────
        // 캘린더 이벤트 제목에 저장된 담당자명은 team-members.json의 name (한글 이름)
        String requesterName = resolveAssigneeName(userId);
        if (requesterName == null || requesterName.isBlank()) {
            log.warn("팀원 이름 조회 실패: userId={} — team-members.json에 등록된 팀원인지 확인 필요", userId);
            sendErrorDm(userId, "⚠️ 팀원 정보를 조회할 수 없어 티켓을 검색하지 못했습니다.\n"
                    + "team-members.json에 본인의 Slack User ID가 등록되어 있어야 합니다.");
            return;
        }

        // ── 현재 분기 범위 계산 ──────────────────────────────────────────────
        LocalDate today = DateTimeUtil.todayKst();
        int quarter = (today.getMonthValue() - 1) / 3 + 1;
        LocalDate quarterStart = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
        LocalDate quarterEnd = LocalDate.of(today.getYear(), quarter * 3, 1)
                .with(TemporalAdjusters.lastDayOfMonth());

        // ── 캘린더는 항상 분기 전체 조회 → Java에서 기간별 필터링 ───────────
        // 캘린더 이벤트의 start.date = duedate 이므로, 범위를 좁히면 조회 안 됨.
        // 분기 전체를 수집 후 period 조건에 맞는 티켓만 필터링한다.
        log.info("현재티켓 캘린더 조회 범위: userId={}, name={}, period={}, 분기={} ~ {}",
                userId, requesterName, period, quarterStart, quarterEnd);

        // ── 분기 전체 티켓 수집 (미완료 필터 포함) ──────────────────────────
        List<TicketItem> allTickets = collectTickets(calendarClient, requesterName,
                quarterStart, quarterEnd);

        // ── 기간별 추가 필터링 ───────────────────────────────────────────────
        //
        // 일별: 현재날짜 <= dueDate (오늘 이후 마감인 미완료 티켓)
        // 주별: 이번주 마지막일(일요일) <= dueDate (이번주 일요일 이후 마감인 미완료 티켓)
        // 분기별: 필터 없음 (미완료 전체)
        List<TicketItem> tickets;
        switch (period) {
            case "daily":
                // dueDate <= 오늘 (오늘 마감 포함, 기한 지난 미완료 포함)
                // 또는 dueDate 없는 티켓 포함
                tickets = allTickets.stream()
                        .filter(t -> t.dueDate == null || !t.dueDate.isAfter(today))
                        .collect(Collectors.toList());
                break;
            case "weekly":
                // dueDate <= 이번주 일요일 (이번주 마감 포함, 기한 지난 미완료 포함)
                // 또는 dueDate 없는 티켓 포함
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate thisSunday = thisMonday.plusDays(6);
                tickets = allTickets.stream()
                        .filter(t -> t.dueDate == null || !t.dueDate.isAfter(thisSunday))
                        .collect(Collectors.toList());
                break;
            case "quarterly":
            default:
                // 분기 전체 미완료 — 추가 필터 없음
                tickets = allTickets;
                break;
        }

        log.info("현재티켓 필터링 완료: userId={}, period={}, 전체={}건 → 필터후={}건",
                userId, period, allTickets.size(), tickets.size());

        // ── DM 채널 열기 + 메시지 전송 ──────────────────────────────────────
        String dmChannelId = slackClient.openDm(userId);
        String message = buildMessageWithChannel(
                tickets, requesterName, period, today, quarter,
                quarterStart, quarterEnd, dmChannelId);
        slackClient.postMessage(message);

        log.info("현재티켓 DM 전송 완료: userId={}, name={}, 티켓={}건",
                userId, requesterName, tickets.size());
    }

    // =========================================================================
    // 내부 — 캐시된 리소스 접근
    // =========================================================================

    /**
     * S3Client를 캐시에서 반환하거나, 없으면 생성 후 캐시한다.
     *
     * <p>기존: 매 호출마다 {@code S3Client.builder().build()} (2회/요청, ~300ms×2)
     * <br>개선: static 캐시로 Lambda 컨테이너 수명 동안 1회만 생성.
     */
    private static S3Client getOrCreateS3Client() {
        S3Client s3 = cachedS3Client;
        if (s3 == null) {
            s3 = S3Client.builder().build();
            cachedS3Client = s3;
            log.info("[CurrentTicketFacade] S3Client 생성 완료 (캐시 저장)");
        }
        return s3;
    }

    /**
     * GoogleCalendarClient를 캐시에서 반환하거나, S3에서 인증 키를 로드하여 생성 후 캐시한다.
     *
     * <p>기존: 매 호출마다 S3Client 생성 + S3 조회 + GoogleCalendarClient 생성 (~1,200ms)
     * <br>개선: static 캐시로 Lambda 컨테이너 수명 동안 1회만 생성.
     * handleCommand()의 사전 초기화 스레드에서 미리 호출되어 캐시 준비.
     *
     * @throws Exception 환경변수 미설정 또는 S3 로드 실패 시
     */
    private static GoogleCalendarClient getOrCreateCalendarClient() throws Exception {
        GoogleCalendarClient client = cachedCalendarClient;
        if (client != null) return client;

        String bucket = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_BUCKET");
        String key = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_KEY");
        if (key == null || key.isBlank()) key = "google-credentials.json";

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("GOOGLE_CALENDAR_CREDENTIALS_BUCKET 환경변수 미설정");
        }

        byte[] credBytes = getOrCreateS3Client().getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
        ).readAllBytes();

        client = new GoogleCalendarClient(credBytes);
        cachedCalendarClient = client;
        log.info("[CurrentTicketFacade] GoogleCalendarClient 초기화 완료 (캐시 저장)");
        return client;
    }

    // =========================================================================
    // 내부 — team-members.json에서 이름 조회 (캐시)
    // =========================================================================

    /**
     * team-members.json에서 Slack User ID로 TeamMember.name(한글 이름)을 조회한다.
     *
     * <p>기존: 매 호출마다 S3Client 생성 + S3 조회 + JSON 파싱 (~400ms)
     * <br>개선: 첫 조회 시 전체 멤버 맵을 static 캐시. 이후 O(1) 조회.
     *
     * <p>캘린더 이벤트 제목 "[Jira] CCE-123 (조주현)" 의 "(조주현)" 부분이
     * team-members.json의 name 필드와 동일하므로 정확한 매칭이 가능하다.
     *
     * @param slackUserId 요청자 Slack User ID
     * @return team-members.json의 name 값, 찾지 못하면 null
     */
    private String resolveAssigneeName(String slackUserId) {
        // ── 캐시 히트 ─────────────────────────────────────────────────────
        Map<String, String> memberMap = cachedTeamMemberMap;
        if (memberMap == null) {
            memberMap = loadTeamMemberMap(configBucket, teamMembersKey);
        }
        if (memberMap == null) return null;

        String name = memberMap.get(slackUserId);
        if (name != null) {
            log.info("[CurrentTicketFacade] 팀원 이름 조회 완료: userId={}, name={}", slackUserId, name);
        } else {
            log.warn("[CurrentTicketFacade] slackUserId={}에 해당하는 팀원 없음", slackUserId);
        }
        return name;
    }

    /**
     * team-members.json을 S3에서 로드하여 slackUserId → name 맵으로 캐시한다.
     *
     * @return 로드된 맵, 실패 시 null
     */
    private static Map<String, String> loadTeamMemberMap(String bucket, String key) {
        Map<String, String> existing = cachedTeamMemberMap;
        if (existing != null) return existing;

        if (bucket == null || bucket.isBlank()) {
            log.warn("[CurrentTicketFacade] CONFIG_BUCKET 미설정 — team-members.json 조회 불가");
            return null;
        }
        try {
            byte[] bytes = getOrCreateS3Client().getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()
            ).readAllBytes();

            JsonNode root = OM.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode members = root.path("members");
            if (members.isMissingNode() || !members.isArray()) {
                log.warn("[CurrentTicketFacade] team-members.json에 'members' 배열 없음");
                return null;
            }

            Map<String, String> map = new HashMap<>();
            for (JsonNode m : members) {
                String sid = m.path("slack_user_id").asText("");
                String name = m.path("name").asText("").trim();
                if (!sid.isEmpty() && !name.isEmpty()) {
                    map.put(sid, name);
                }
            }
            cachedTeamMemberMap = map;
            log.info("[CurrentTicketFacade] team-members.json 캐시 완료: {}명", map.size());
            return map;
        } catch (Exception e) {
            log.error("[CurrentTicketFacade] team-members.json 로드 실패: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // 내부 — 캘린더 이벤트 수집
    // =========================================================================

    /**
     * 지정 기간의 캘린더 이벤트에서 요청자 담당 미완료 티켓만 추출한다.
     *
     * <p>DailyCalendarTicketCollector.parseTicketEvent() 와 동일한 파싱 로직.
     *
     * @return 미완료 티켓 목록 (날짜 오름차순 → 중요도 오름차순 정렬 완료)
     */
    private List<TicketItem> collectTickets(
            GoogleCalendarClient calendarClient,
            String requesterName,
            LocalDate from,
            LocalDate to) {

        String timeMin = from + "T00:00:00+09:00";
        String timeMax = to + "T23:59:59+09:00";

        List<Event> events;
        try {
            // searchQuery=null: 캘린더 전체 조회 후 Java에서 이름 매칭
            // requesterName을 searchQuery로 넘기면 Google API full-text 검색이 되어
            // 이름이 제목에 있어도 누락되거나 오매칭될 수 있음
            events = calendarClient.listEvents(ticketCalendarId, timeMin, timeMax, null);
        } catch (Exception e) {
            log.error("현재티켓 캘린더 조회 실패: from={}, to={}", from, to, e);
            return List.of();
        }

        log.info("현재티켓 캘린더 이벤트 수신: 전체={}건, 담당자 필터={}", events.size(), requesterName);

        List<TicketItem> tickets = new ArrayList<>();
        LocalDate today = DateTimeUtil.todayKst();

        for (Event event : events) {
            try {
                TicketItem item = parseTicketEvent(event, requesterName, today);
                if (item == null) continue;
                if (item.status == JiraStatusCode.DONE) continue; // 미완료 필터
                tickets.add(item);
            } catch (Exception e) {
                log.warn("현재티켓 이벤트 파싱 실패: title={}", event.getSummary(), e);
            }
        }

        // 정렬: 날짜 오름차순 → 중요도(priority.order) 오름차순
        tickets.sort(Comparator
                .comparing((TicketItem t) -> t.dueDate == null ? LocalDate.MAX : t.dueDate)
                .thenComparingInt(t -> t.priority.getOrder()));

        log.info("현재티켓 수집 완료: name={}, 미완료={}건", requesterName, tickets.size());
        return tickets;
    }

    // =========================================================================
    // 내부 — 이벤트 파싱 (DailyCalendarTicketCollector와 동일 로직)
    // =========================================================================

    /**
     * 캘린더 이벤트 → TicketItem 변환.
     *
     * @return null 이면 이슈 키 없음 또는 담당자 불일치
     */
    private TicketItem parseTicketEvent(Event event, String requesterName, LocalDate today) {
        String title = event.getSummary();
        if (title == null) return null;

        Matcher keyMatcher = ISSUE_KEY_PATTERN.matcher(title);
        if (!keyMatcher.find()) return null;
        String issueKey = keyMatcher.group(1) != null ? keyMatcher.group(1) : keyMatcher.group(2);

        List<String> assigneeNames = parseAssigneeNames(title);
        // contains 매칭: Slack real_name과 캘린더 이름이 완전히 일치하지 않아도
        // team-members.json의 name과 캘린더 이벤트 제목의 담당자명을 정확히 비교
        boolean isAssigned = assigneeNames.isEmpty()
                || assigneeNames.stream().anyMatch(n -> n.trim().equals(requesterName.trim()));
        if (!isAssigned) {
            log.debug("담당자 불일치: title={}, assignees={}, requester={}",
                    title, assigneeNames, requesterName);
            return null;
        }

        LocalDate dueDate = dateOf(event);
        JiraPriorityCode priority = detectPriority(event.getDescription());
        JiraStatusCode status = detectStatus(event.getDescription());
        DueDateUrgencyCode urgency = DueDateUrgencyCode.of(today, dueDate);

        String summary = extractTitleFromDescription(event.getDescription());
        if (summary == null || summary.isBlank()) summary = extractSummaryFromTitle(title);
        if (summary == null || summary.isBlank()) summary = issueKey;

        return new TicketItem(issueKey, summary, status, priority, dueDate, urgency,
                jiraBaseUrl + "/browse/" + issueKey);
    }

    private List<String> parseAssigneeNames(String title) {
        if (title == null) return List.of();
        Matcher m = ASSIGNEE_PATTERN.matcher(title);
        if (!m.find()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            String n = part.trim();
            if (!n.isEmpty()) names.add(n);
        }
        return names;
    }

    private LocalDate dateOf(Event event) {
        try {
            if (event.getStart() == null) return null;
            if (event.getStart().getDate() != null)
                return LocalDate.parse(event.getStart().getDate().toString());
            if (event.getStart().getDateTime() != null) {
                String dt = event.getStart().getDateTime().toString();
                return LocalDate.parse(dt.length() >= 10 ? dt.substring(0, 10) : dt);
            }
        } catch (Exception e) {
            log.debug("날짜 파싱 실패: eventId={}", event.getId());
        }
        return null;
    }

    /**
     * description의 "Priority: ..." 라인에서 우선순위를 파싱한다.
     *
     * <p><b>버그 수정:</b> 기존에는 title에서 이모지를 감지했으나,
     * CalendarService가 생성하는 이벤트 제목은 "[Jira] CCE-123 (이름)" 형식이며
     * 이모지가 없다. 우선순위는 description의 "Priority: High" 라인에 저장된다.
     * 따라서 title 기반 감지는 항상 MEDIUM을 반환하는 버그였다.
     *
     * @param description 캘린더 이벤트 description (null 허용)
     */
    private JiraPriorityCode detectPriority(String description) {
        if (description == null || description.isBlank()) return JiraPriorityCode.MEDIUM;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Priority: ")) {
                String p = trimmed.substring("Priority: ".length()).trim().toLowerCase();
                switch (p) {
                    case "highest":
                        return JiraPriorityCode.HIGHEST;
                    case "high":
                        return JiraPriorityCode.HIGH;
                    case "medium":
                        return JiraPriorityCode.MEDIUM;
                    case "low":
                        return JiraPriorityCode.LOW;
                    case "lowest":
                        return JiraPriorityCode.LOWEST;
                    default:
                        return JiraPriorityCode.MEDIUM;
                }
            }
        }
        return JiraPriorityCode.MEDIUM;
    }

    private JiraStatusCode detectStatus(String description) {
        if (description == null || description.isBlank()) return JiraStatusCode.IN_PROGRESS;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Status: ")) {
                return JiraStatusCode.fromStatusName(trimmed.substring("Status: ".length()).trim());
            }
        }
        return JiraStatusCode.IN_PROGRESS;
    }

    private String extractTitleFromDescription(String description) {
        if (description == null || description.isBlank()) return null;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Title: ")) {
                String t = trimmed.substring("Title: ".length()).trim();
                return t.isBlank() ? null : t;
            }
        }
        return null;
    }

    private String extractSummaryFromTitle(String title) {
        if (title == null) return null;
        String cleaned = ISSUE_KEY_PATTERN.matcher(title).replaceAll("").trim();
        cleaned = ASSIGNEE_PATTERN.matcher(cleaned).replaceAll("").trim();
        cleaned = cleaned.replaceAll("^\\s*[-_|]\\s*", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    // =========================================================================
    // 내부 — Slack 메시지 구성
    // =========================================================================

    /**
     * 티켓 조회 결과 Slack Block Kit 메시지 JSON 생성.
     *
     * <p>일일 보고서의 티켓 현황 포맷과 동일:
     * 날짜별 그룹핑(날짜 헤더 bold) + 티켓 행(링크 + 제목 + 우선순위이모지).
     * 기한 초과 미완료는 백틱 강조, 당일 마감은 bold 처리.
     */
    private String buildMessageWithChannel(
            List<TicketItem> tickets,
            String requesterName,
            String period,
            LocalDate today,
            int quarter,
            LocalDate quarterStart,
            LocalDate quarterEnd,
            String channelId) {

        String periodTitle = buildPeriodTitle(period);
        String periodDetail = buildPeriodDetail(period, today, quarter, quarterStart, quarterEnd);

        SlackBlockBuilder builder = SlackBlockBuilder
                .forChannel(channelId)
                .fallbackText(periodTitle + " | " + requesterName)
                .noUnfurl();

        // 헤더: "일별 미완료 티켓 조회  |  조주현"
        builder.header(periodTitle + "  |  " + requesterName);

        // 컨텍스트: 조회 범위 + 건수 + 시각
        builder.context(periodDetail
                + "   |   *" + tickets.size() + "*건"
                + "   |   " + DateTimeUtil.formatDateTime(DateTimeUtil.nowKst()) + " KST");

        builder.divider();

        if (tickets.isEmpty()) {
            builder.section(INDENT + "_조회된 티켓이 없습니다._");
        } else {
            appendTicketSection(builder, tickets, today);
        }

        return builder.build();
    }

    /**
     * 티켓 목록을 날짜별로 그룹핑하여 섹션 블록으로 추가한다.
     */
    private void appendTicketSection(
            SlackBlockBuilder builder,
            List<TicketItem> tickets,
            LocalDate today) {

        LinkedHashMap<LocalDate, List<TicketItem>> groups = new LinkedHashMap<>();
        for (TicketItem t : tickets) {
            groups.computeIfAbsent(t.dueDate, k -> new ArrayList<>()).add(t);
        }

        final int SECTION_LIMIT = 2800;
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<LocalDate, List<TicketItem>> entry : groups.entrySet()) {
            LocalDate due = entry.getKey();
            String dateHdr = (due == null) ? "*기한없음*" : "*" + formatDayLabel(due) + "*";
            String hdrLine = INDENT + dateHdr + "\n";
            if (sb.length() + hdrLine.length() > SECTION_LIMIT) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder();
            }
            sb.append(hdrLine);

            for (TicketItem t : entry.getValue()) {
                String line = formatTicketLine(t, today) + "\n";
                if (sb.length() + line.length() > SECTION_LIMIT) {
                    builder.section(sb.toString().trim());
                    sb = new StringBuilder();
                }
                sb.append(line);
            }
        }

        if (sb.length() > 0) {
            builder.section(sb.toString().trim());
        }
    }

    /**
     * 티켓 한 줄 포맷.
     *
     * <pre>
     *   당일 마감         : bold      *[KEY] 제목*  뱃지
     *   기한 초과 + 미완료 : 백틱 강조  `[KEY]` `제목`  뱃지
     *   일반              : plain     [KEY] 제목  뱃지
     * </pre>
     *
     * <p>우선순위 뱃지: Highest → (🔺Highest) / High → (🔸High) / Medium 이하 → 없음
     */
    private String formatTicketLine(TicketItem t, LocalDate today) {
        String badge = buildPriorityBadge(t.priority);
        String link = "<" + t.url + "|[" + t.issueKey + "]>";
        boolean isOverdue = t.dueDate != null && t.dueDate.isBefore(today);
        boolean isDueToday = t.dueDate != null && t.dueDate.isEqual(today);

        if (isDueToday) {
            return INDENT2 + "• *" + link + " " + t.summary + "*" + badge;
        } else if (isOverdue) {
            String issueLink = "<" + t.url + "|`[" + t.issueKey + "]`>";
            return INDENT2 + "• " + issueLink + " `" + t.summary + "`" + badge;
        } else {
            return INDENT2 + "• " + link + " " + t.summary + badge;
        }
    }

    /**
     * 우선순위 뱃지 반환.
     * Highest/High만 표시, Medium 이하는 빈 문자열 (노이즈 제거).
     */
    private String buildPriorityBadge(JiraPriorityCode priority) {
        if (priority == null) return "";
        switch (priority) {
            case HIGHEST:
                return "  (🔺Highest)";
            case HIGH:
                return "  (🔸High)";
            default:
                return "";
        }
    }

    private String formatDayLabel(LocalDate date) {
        return String.format("%02d/%02d(%s)",
                date.getMonthValue(),
                date.getDayOfMonth(),
                DateTimeUtil.DISPLAY_FMT.format(date).replaceAll(".*\\((.*)\\).*", "$1"));
    }

    /**
     * 헤더용 기간 제목: "일별 미완료 티켓 조회", "주별 미완료 티켓 조회", "분기별 미완료 티켓 조회"
     */
    private String buildPeriodTitle(String period) {
        switch (period) {
            case "daily":
                return "📅 일별 미완료 티켓 조회";
            case "weekly":
                return "📆 주별 미완료 티켓 조회";
            case "quarterly":
            default:
                return "🗓️ 분기별 미완료 티켓 조회";
        }
    }

    /**
     * 컨텍스트용 기간 상세: 조회 범위 표시
     */
    private String buildPeriodDetail(
            String period, LocalDate today, int quarter,
            LocalDate quarterStart, LocalDate quarterEnd) {
        switch (period) {
            case "daily":
                return "기준일: *" + DateTimeUtil.formatDate(today) + "* 이하 마감";
            case "weekly":
                LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate sun = mon.plusDays(6);
                return "기준주: *" + DateTimeUtil.formatDate(mon) + " ~ " + DateTimeUtil.formatDate(sun) + "* 이하 마감";
            case "quarterly":
            default:
                return today.getYear() + " Q" + quarter
                        + "  (*" + DateTimeUtil.formatDate(quarterStart)
                        + " ~ " + DateTimeUtil.formatDate(quarterEnd) + "*)";
        }
    }

    private void sendErrorDm(String userId, String message) {
        try {
            String dmChannelId = slackClient.openDm(userId);
            String payload = SlackBlockBuilder.forChannel(dmChannelId)
                    .fallbackText(message)
                    .section(message)
                    .build();
            slackClient.postMessage(payload);
        } catch (Exception e) {
            log.error("현재티켓 오류 DM 전송 실패: userId={}", userId, e);
        }
    }

    // =========================================================================
    // 내부 VO — 경량 티켓 아이템
    // =========================================================================

    /**
     * 현재티켓 조회용 경량 티켓 VO.
     *
     * <p>DailyReportData.TicketItem 은 scheduler 모듈 소속이므로
     * ingest 모듈에서 직접 참조하지 않고 별도로 정의한다.
     */
    private static class TicketItem {
        final String issueKey;
        final String summary;
        final JiraStatusCode status;
        final JiraPriorityCode priority;
        final LocalDate dueDate;
        final DueDateUrgencyCode urgency;
        final String url;

        TicketItem(String issueKey, String summary,
                   JiraStatusCode status, JiraPriorityCode priority,
                   LocalDate dueDate, DueDateUrgencyCode urgency, String url) {
            this.issueKey = issueKey;
            this.summary = summary;
            this.status = status;
            this.priority = priority;
            this.dueDate = dueDate;
            this.urgency = urgency;
            this.url = url;
        }
    }
}
