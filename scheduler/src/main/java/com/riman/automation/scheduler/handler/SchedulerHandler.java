package com.riman.automation.scheduler.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.riman.automation.clients.anthropic.AnthropicClient;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.confluence.ConfluenceClient;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.BasicTokenProvider;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.exception.AutomationException;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.facade.DailyReportFacade;
import com.riman.automation.scheduler.facade.MonthlyReportFacade;
import com.riman.automation.scheduler.facade.WeeklyReportFacade;
import com.riman.automation.scheduler.service.collect.DailyAbsenceCollector;
import com.riman.automation.scheduler.service.collect.DailyCalendarTicketCollector;
import com.riman.automation.scheduler.service.collect.DailyScheduleCollector;
import com.riman.automation.scheduler.service.collect.MonthlyCalendarTicketCollector;
import com.riman.automation.scheduler.service.collect.WeeklyCalendarTicketCollector;
import com.riman.automation.scheduler.service.load.ReportRulesService;
import com.riman.automation.scheduler.service.load.TeamMemberService;
import com.riman.automation.scheduler.service.format.DailyReportFormatter;
import com.riman.automation.scheduler.service.report.DailyReportService;
import com.riman.automation.scheduler.service.excel.MonthlyExcelGenerator;
import com.riman.automation.scheduler.service.format.MonthlyReportFormatter;
import com.riman.automation.scheduler.service.report.MonthlyReportService;
import com.riman.automation.scheduler.service.excel.WeeklyExcelGenerator;
import com.riman.automation.scheduler.service.format.WeeklyReportFormatter;
import com.riman.automation.scheduler.service.report.WeeklyReportService;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.LocalDate;
import java.util.Map;

/**
 * EventBridge Scheduler → Lambda 진입점
 *
 * <p><b>입력 타입을 Map으로 변경한 이유 (버그 수정):</b><br>
 * {@code ScheduledEvent}는 EventBridge Rules 전용 클래스로,
 * EventBridge Scheduler가 보내는 페이로드의 "time":""(빈 문자열)를
 * Joda-Time DateTime으로 파싱하려다 {@code IllegalArgumentException} 발생.<br>
 * {@code Map<String, Object>}로 raw 수신하면 이 문제가 원천 차단됨.
 *
 * <p><b>report_type 분기:</b>
 * <pre>
 *   event.report_type = "daily"   → DailyReportFacade.runDaily()
 *   event.report_type = "weekly"  → WeeklyReportFacade.runWeekly()
 *   event.report_type = "monthly" → MonthlyReportFacade.runMonthly()
 *   미지정                         → "daily" 기본 동작
 * </pre>
 *
 * <p><b>time 필드 사용 규칙 (3가지 타입 공통):</b>
 * <pre>
 *   daily   : time = "2026-03-14" (yyyy-MM-dd)
 *               → 해당 날짜 기준 일일 보고서 생성
 *               → 미지정 시 KST 오늘 날짜 사용
 *
 *   weekly  : time = "2026-03-10" (yyyy-MM-dd)
 *               → 해당 날짜 기준 전주(월~일) 보고서 생성
 *               → 미지정 시 KST 오늘 날짜 기준 전주 사용
 *
 *   monthly : time = "2026-03" (yyyy-MM)
 *               → 해당 연월 기준 월간 보고서 생성
 *               → 미지정 시 KST 오늘 기준 이전 월 자동 사용
 * </pre>
 *
 * <p><b>수동 테스트 예시:</b>
 * <pre>
 *   // daily — 2026-03-14 일일 보고서 생성
 *   { "report_type": "daily", "time": "2026-03-14" }
 *
 *   // weekly — 2026-03-02(월) ~ 2026-03-08(일) 주간 보고서 생성
 *   { "report_type": "weekly", "time": "2026-03-10" }
 *
 *   // monthly — 2026년 3월 월간 보고서 생성
 *   { "report_type": "monthly", "time": "2026-03" }
 *
 *   // EventBridge 자동 실행 (time 없음 → 각 타입별 기본 동작)
 *   { "report_type": "daily" }    → KST 오늘 기준
 *   { "report_type": "weekly" }   → KST 오늘 기준 전주
 *   { "report_type": "monthly" }  → KST 오늘 기준 이전 월
 * </pre>
 *
 * <p><b>설정 파일 분리:</b>
 * <pre>
 *   config.json           ingest/worker 전용, 이 Lambda는 읽지 않음
 *   scheduler-config.json scheduler 전용 (SCHEDULER_CONFIG_KEY 환경변수로 지정)
 * </pre>
 *
 * <p><b>Lambda 환경변수:</b>
 * <pre>
 *  필수
 *   CONFIG_BUCKET                       S3 버킷
 *   SCHEDULER_CONFIG_KEY                scheduler-config.json S3 키 (기본값: scheduler-config.json)
 *   SLACK_REPORT_BOT_TOKEN              Slack Bot Token (xoxb-...)
 *   JIRA_BASE_URL                       https://riman-it.atlassian.net
 *   JIRA_EMAIL                          Jira 인증 이메일
 *   JIRA_API_TOKEN                      Jira API Token
 *   GOOGLE_CALENDAR_CREDENTIALS_BUCKET  google-credentials.json S3 버킷
 *   GOOGLE_CALENDAR_CREDENTIALS_KEY     google-credentials.json S3 키 (기본값: google-credentials.json)
 *  선택
 *   ANTHROPIC_API_KEY                   없으면 AI 후처리 없이 동작
 *   SCHEDULE_MAPPING_TABLE              DynamoDB 테이블명, 없으면 오늘 일정 섹션 미출력
 *   CONFLUENCE_BASE_URL                 없으면 주간/월간보고 비활성
 *   CONFLUENCE_SPACE_KEY                없으면 주간/월간보고 비활성
 * </pre>
 */
@Slf4j
public class SchedulerHandler implements RequestHandler<Map<String, Object>, String> {

    // =========================================================================
    // static 싱글톤 — Lambda cold start 시 1회 초기화
    // =========================================================================

    /**
     * 일일 보고서 오케스트레이터
     */
    private static final DailyReportFacade dailyOrchestrator;

    /**
     * 주간 실적 보고서 오케스트레이터 (CONFLUENCE_BASE_URL 미설정 시 null)
     */
    private static final WeeklyReportFacade weeklyOrchestrator;

    /**
     * 월간 실적 보고서 오케스트레이터 (CONFLUENCE_BASE_URL 미설정 시 null)
     */
    private static final MonthlyReportFacade monthlyOrchestrator;

    static {
        log.info("[SchedulerHandler] Lambda cold start: 의존성 초기화");

        // ── 공통 환경변수 ────────────────────────────────────────────────────
        String configBucket = requireEnv("CONFIG_BUCKET");
        String schedulerConfigKey = getEnvOrDefault("SCHEDULER_CONFIG_KEY", "scheduler-config.json");
        String jiraBaseUrl = requireEnv("JIRA_BASE_URL");

        // ── 공통 AWS 클라이언트 ──────────────────────────────────────────────
        S3Client s3 = S3Client.builder()
                .region(Region.AP_NORTHEAST_2)
                .build();

        // ── 공통 외부 API 클라이언트 ─────────────────────────────────────────
        SlackClient slackClient = new SlackClient(
                new EnvTokenProvider("SLACK_REPORT_BOT_TOKEN"));

        JiraClient jiraClient = new JiraClient(
                jiraBaseUrl, new BasicTokenProvider("JIRA_EMAIL", "JIRA_API_TOKEN"));

        GoogleCalendarClient calendarClient = new GoogleCalendarClient(
                loadGoogleCredentials(s3));

        // ── Daily / Weekly / Monthly 공통 — 팀원 목록 서비스 ────────────────
        TeamMemberService teamMemberService = new TeamMemberService(s3, configBucket);

        // ── 일일 보고 의존성 ─────────────────────────────────────────────────
        DailyCalendarTicketCollector ticketCollector =
                new DailyCalendarTicketCollector(calendarClient, jiraBaseUrl, jiraClient);
        DailyAbsenceCollector absenceCollector = new DailyAbsenceCollector(calendarClient);
        DailyReportFormatter formatter = new DailyReportFormatter();

        // AI 후처리: ANTHROPIC_API_KEY 설정 시 활성화
        DailyReportService aiRefiner = buildAiRefiner(formatter, s3, configBucket);

        // 오늘 일정 수집: SCHEDULE_MAPPING_TABLE 설정 시 활성화
        DailyScheduleCollector scheduleCollector = buildScheduleCollector(calendarClient);

        dailyOrchestrator = new DailyReportFacade(
                s3, configBucket, schedulerConfigKey,
                teamMemberService,
                ticketCollector, absenceCollector,
                scheduleCollector,
                formatter, aiRefiner,
                slackClient);

        // ── 주간 보고 오케스트레이터 ─────────────────────────────────────────
        // CONFLUENCE_BASE_URL / CONFLUENCE_SPACE_KEY 미설정 시 null
        // ConfluenceClient는 weekly/monthly가 동일 설정이므로 단일 인스턴스 공유
        ConfluenceClient sharedConfluenceClient = buildConfluenceClient();

        weeklyOrchestrator = buildWeeklyOrchestrator(
                s3, configBucket, schedulerConfigKey,
                calendarClient, jiraClient, jiraBaseUrl,
                teamMemberService, sharedConfluenceClient);

        // ── 월간 보고 오케스트레이터 ─────────────────────────────────────────
        // CONFLUENCE_BASE_URL / CONFLUENCE_SPACE_KEY 미설정 시 null
        monthlyOrchestrator = buildMonthlyOrchestrator(
                s3, configBucket, schedulerConfigKey,
                calendarClient, jiraClient, jiraBaseUrl,
                teamMemberService, sharedConfluenceClient);

        log.info("[SchedulerHandler] 초기화 완료 (AI={}, schedule={}, weekly={}, monthly={}, configKey={})",
                aiRefiner != null ? "활성" : "비활성",
                scheduleCollector != null ? "활성" : "비활성",
                weeklyOrchestrator != null ? "활성" : "비활성",
                monthlyOrchestrator != null ? "활성" : "비활성",
                schedulerConfigKey);
    }

    // =========================================================================
    // Lambda 핸들러 진입점
    // =========================================================================

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        String source = (event != null)
                ? String.valueOf(event.getOrDefault("source", "unknown"))
                : "unknown";
        log.info("[SchedulerHandler] 이벤트 수신: source={}", source);

        // time 필드 — 타입별로 형식과 의미가 다름 (아래 각 메서드 참조)
        String timeValue = extractTimeValue(event);

        try {
            String reportType = (event != null)
                    ? String.valueOf(event.getOrDefault("report_type", "daily")).toLowerCase()
                    : "daily";

            return switch (reportType) {
                case "weekly" -> runWeekly(timeValue);
                case "monthly" -> runMonthly(timeValue);
                default -> runDaily(timeValue);
            };

        } catch (ConfigException e) {
            log.error("[SchedulerHandler] 설정 오류: {}", e.getMessage(), e);
            return "CONFIG_ERROR: " + e.getMessage();
        } catch (AutomationException e) {
            log.error("[SchedulerHandler] 자동화 오류: {} {}", e.getErrorCode(), e.getMessage());
            return "ERROR: " + e.getErrorCode() + " " + e.getMessage();
        } catch (Exception e) {
            log.error("[SchedulerHandler] 예상치 못한 오류", e);
            return "UNEXPECTED_ERROR: " + e.getMessage();
        }
    }

    // =========================================================================
    // 보고서 타입별 실행 메서드
    // =========================================================================

    /**
     * 일일 보고서 실행.
     *
     * <p><b>baseDate 결정:</b>
     * <ul>
     *   <li>time = "2026-03-14" → 해당 날짜 기준 일일 보고서 생성</li>
     *   <li>미지정 → KST 오늘 날짜 사용 (EventBridge 자동 실행)</li>
     * </ul>
     */
    private String runDaily(String timeValue) {
        LocalDate baseDate = parseDateOrToday(timeValue);
        log.info("[SchedulerHandler] 일일보고 실행: baseDate={}", baseDate);
        dailyOrchestrator.runDaily(baseDate);
        return "SUCCESS";
    }

    /**
     * 주간 실적 보고서 실행.
     *
     * <p><b>baseDate 결정:</b>
     * <ul>
     *   <li>time = "2026-03-10" → 해당 날짜 기준 전주(월~일) 보고서 생성</li>
     *   <li>미지정 → KST 오늘 날짜 기준 전주 사용 (EventBridge 매주 월요일 자동 실행)</li>
     * </ul>
     *
     * <p>CONFLUENCE 환경변수 미설정 시 스킵.
     */
    private String runWeekly(String timeValue) {
        if (weeklyOrchestrator == null) {
            log.warn("[SchedulerHandler] 주간보고 비활성 — weeklyOrchestrator 미초기화");
            return "SKIPPED: weeklyOrchestrator not initialized";
        }
        LocalDate baseDate = parseDateOrToday(timeValue);
        log.info("[SchedulerHandler] 주간보고 실행: baseDate={}", baseDate);
        weeklyOrchestrator.runWeekly(baseDate);
        return "SUCCESS";
    }

    /**
     * 월간 실적 보고서 실행.
     *
     * <p><b>대상 월 결정:</b>
     * <ul>
     *   <li>time = "2026-03" (yyyy-MM) → 해당 월(3월) 보고서 생성</li>
     *   <li>미지정 → KST 오늘 기준 이전 월 자동 사용 (매월 1일 EventBridge 자동 실행 시 전월)</li>
     * </ul>
     *
     * <p>CONFLUENCE 환경변수 미설정 시 스킵.
     */
    private String runMonthly(String timeValue) {
        if (monthlyOrchestrator == null) {
            log.warn("[SchedulerHandler] 월간보고 비활성 — monthlyOrchestrator 미초기화");
            return "SKIPPED: monthlyOrchestrator not initialized";
        }

        // time = "yyyy-MM" 형식이면 targetMonth 로 사용
        // 미지정이면 null → MonthlyReportFacade 에서 KST 오늘 기준 이전 월 자동 사용
        String targetMonth = parseYearMonthOrNull(timeValue);

        // baseDate 는 항상 KST 오늘 (monthly 는 targetMonth 가 날짜를 결정)
        LocalDate baseDate = DateTimeUtil.todayKst();

        log.info("[SchedulerHandler] 월간보고 실행: baseDate={}, targetMonth={}",
                baseDate, targetMonth != null ? targetMonth : "(자동: 이전 월)");
        monthlyOrchestrator.runMonthly(baseDate, targetMonth);
        return "SUCCESS";
    }

    // =========================================================================
    // 내부 헬퍼 — time 파싱
    // =========================================================================

    /**
     * event.time 필드 값을 문자열로 추출한다.
     *
     * <p>EventBridge Scheduler가 "time":""(빈 문자열)을 보내는 경우가 있으므로
     * 빈 문자열은 null 로 정규화하여 반환한다.
     *
     * @return time 값 문자열, 없거나 빈 문자열이면 null
     */
    private static String extractTimeValue(Map<String, Object> event) {
        if (event == null) return null;
        Object val = event.get("time");
        if (!(val instanceof String str)) return null;
        return str.isBlank() ? null : str.trim();
    }

    /**
     * time 문자열을 "yyyy-MM-dd" 형식으로 파싱하여 LocalDate 반환.
     *
     * <p>파싱 규칙:
     * <ul>
     *   <li>null 또는 빈 문자열 → KST 오늘 날짜 반환</li>
     *   <li>"2026-03-14" (10자 이상) → 앞 10자리 파싱</li>
     *   <li>파싱 실패 → 경고 로그 후 KST 오늘 날짜 반환</li>
     * </ul>
     *
     * <p>daily / weekly 에서 사용.
     */
    private static LocalDate parseDateOrToday(String timeValue) {
        if (timeValue == null) {
            LocalDate today = DateTimeUtil.todayKst();
            log.info("[SchedulerHandler] time 미지정 → KST today 사용: {}", today);
            return today;
        }
        try {
            String datePart = timeValue.length() >= 10 ? timeValue.substring(0, 10) : timeValue;
            LocalDate parsed = LocalDate.parse(datePart);
            log.info("[SchedulerHandler] baseDate from time: {}", parsed);
            return parsed;
        } catch (Exception e) {
            LocalDate today = DateTimeUtil.todayKst();
            log.warn("[SchedulerHandler] time 파싱 실패 '{}', KST today 사용: {}", timeValue, today);
            return today;
        }
    }

    /**
     * time 문자열을 "yyyy-MM" 형식으로 파싱하여 반환.
     *
     * <p>파싱 규칙:
     * <ul>
     *   <li>null → null 반환 (MonthlyReportFacade 에서 이전 월 자동 처리)</li>
     *   <li>"2026-03" (7자) → 그대로 반환</li>
     *   <li>"2026-03-14" 처럼 날짜 형식이 들어오면 앞 7자리만 사용</li>
     *   <li>"yyyy-MM" 형식이 아니면 → 경고 로그 후 null 반환</li>
     * </ul>
     *
     * <p>monthly 에서만 사용.
     *
     * @return "yyyy-MM" 형식 문자열, 파싱 불가 시 null
     */
    private static String parseYearMonthOrNull(String timeValue) {
        if (timeValue == null) return null;
        try {
            // "yyyy-MM" 형식인지 검증 — 앞 7자리로 LocalDate 파싱 시도 ("yyyy-MM-01")
            String yearMonth = timeValue.length() >= 7 ? timeValue.substring(0, 7) : timeValue;
            LocalDate.parse(yearMonth + "-01"); // 검증용 파싱
            log.info("[SchedulerHandler] targetMonth from time: {}", yearMonth);
            return yearMonth;
        } catch (Exception e) {
            log.warn("[SchedulerHandler] time 월 파싱 실패 '{}', 이전 월 자동 사용", timeValue);
            return null;
        }
    }

    // =========================================================================
    // 내부 헬퍼 — 의존성 빌더
    // =========================================================================

    /**
     * Google Calendar 서비스 계정 키 파일을 S3에서 로드한다.
     */
    private static byte[] loadGoogleCredentials(S3Client s3) {
        String bucket = requireEnv("GOOGLE_CALENDAR_CREDENTIALS_BUCKET");
        String key = getEnvOrDefault("GOOGLE_CALENDAR_CREDENTIALS_KEY", "google-credentials.json");
        try {
            log.info("[SchedulerHandler] Google credentials 로드: {}/{}", bucket, key);
            byte[] bytes = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()
            ).readAllBytes();
            log.info("[SchedulerHandler] Google credentials 로드 완료: {} bytes", bytes.length);
            return bytes;
        } catch (Exception e) {
            throw new ConfigException(
                    "google-credentials.json S3 로드 실패: " + bucket + "/" + key, e);
        }
    }

    /**
     * AI 보고서 다듬기 서비스 빌드.
     * ANTHROPIC_API_KEY 미설정 시 null 반환 — DailyReportFacade 원본 포맷 사용.
     */
    private static DailyReportService buildAiRefiner(DailyReportFormatter formatter,
                                                     S3Client s3, String configBucket) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            log.info("[SchedulerHandler] ANTHROPIC_API_KEY 미설정 → AI 후처리 비활성");
            return null;
        }
        AnthropicClient anthropic = new AnthropicClient(new EnvTokenProvider("ANTHROPIC_API_KEY"));
        ReportRulesService rulesLoader = new ReportRulesService(s3, configBucket);
        return new DailyReportService(anthropic, formatter, rulesLoader);
    }

    /**
     * 오늘 일정 수집 서비스 빌드.
     * SCHEDULE_MAPPING_TABLE 미설정 시 null 반환 — 오늘 일정 섹션 생략.
     */
    private static DailyScheduleCollector buildScheduleCollector(GoogleCalendarClient calendarClient) {
        String tableName = System.getenv("SCHEDULE_MAPPING_TABLE");
        if (tableName == null || tableName.isBlank()) {
            log.info("[SchedulerHandler] SCHEDULE_MAPPING_TABLE 미설정 → 오늘 일정 수집 비활성");
            return null;
        }
        DynamoDbClient dynamoDb = DynamoDbClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .build();
        log.info("[SchedulerHandler] DailyScheduleCollector 초기화: table={}", tableName);
        return new DailyScheduleCollector(dynamoDb, calendarClient);
    }

    /**
     * 주간 실적 보고 오케스트레이터 빌드.
     * CONFLUENCE_BASE_URL / CONFLUENCE_SPACE_KEY 미설정 시 null 반환.
     * ConfluenceClient는 외부에서 주입받아 월간 오케스트레이터와 공유한다.
     */
    private static WeeklyReportFacade buildWeeklyOrchestrator(
            S3Client s3, String configBucket, String schedulerConfigKey,
            GoogleCalendarClient calendarClient,
            JiraClient jiraClient, String jiraBaseUrl,
            TeamMemberService teamMemberService,
            ConfluenceClient confluenceClient) {

        if (confluenceClient == null) {
            log.info("[SchedulerHandler] CONFLUENCE 미설정 → 주간보고 비활성");
            return null;
        }

        WeeklyCalendarTicketCollector weeklyTicketCollector =
                new WeeklyCalendarTicketCollector(calendarClient, jiraBaseUrl, jiraClient);
        WeeklyReportFormatter weeklyFormatter = new WeeklyReportFormatter();
        WeeklyReportService weeklyReportService = new WeeklyReportService(
                confluenceClient, new WeeklyExcelGenerator());

        log.info("[SchedulerHandler] WeeklyReportFacade 초기화");

        return new WeeklyReportFacade(
                s3, configBucket, schedulerConfigKey,
                teamMemberService, weeklyTicketCollector,
                weeklyFormatter, weeklyReportService);
    }

    /**
     * 월간 실적 보고 오케스트레이터 빌드.
     * CONFLUENCE_BASE_URL / CONFLUENCE_SPACE_KEY 미설정 시 null 반환.
     * ConfluenceClient는 외부에서 주입받아 주간 오케스트레이터와 공유한다.
     *
     * <p>Confluence 페이지 계층:
     * <pre>
     *   실적보고
     *     └─ 2026년 월간          ← 주간의 "2026년 주간" 과 구분
     *         └─ 2026년 월간 Q1
     *             └─ 2026 Q1 1월 - {team} 실적
     * </pre>
     */
    private static MonthlyReportFacade buildMonthlyOrchestrator(
            S3Client s3, String configBucket, String schedulerConfigKey,
            GoogleCalendarClient calendarClient,
            JiraClient jiraClient, String jiraBaseUrl,
            TeamMemberService teamMemberService,
            ConfluenceClient confluenceClient) {

        if (confluenceClient == null) {
            log.info("[SchedulerHandler] CONFLUENCE 미설정 → 월간보고 비활성");
            return null;
        }

        MonthlyCalendarTicketCollector monthlyTicketCollector =
                new MonthlyCalendarTicketCollector(calendarClient, jiraBaseUrl, jiraClient);
        MonthlyReportFormatter monthlyFormatter = new MonthlyReportFormatter();
        MonthlyReportService monthlyReportService = new MonthlyReportService(
                confluenceClient, new MonthlyExcelGenerator());

        log.info("[SchedulerHandler] MonthlyReportFacade 초기화");

        return new MonthlyReportFacade(
                s3, configBucket, schedulerConfigKey,
                teamMemberService, monthlyTicketCollector,
                monthlyFormatter, monthlyReportService);
    }

    // =========================================================================
    // 환경변수 유틸
    // =========================================================================

    /**
     * Confluence 클라이언트 빌드.
     * CONFLUENCE_BASE_URL / CONFLUENCE_SPACE_KEY 미설정 시 null 반환.
     * 주간/월간 오케스트레이터가 동일한 Confluence 설정을 사용하므로
     * 단일 인스턴스를 생성하여 HTTP 커넥션 풀 중복 생성을 방지한다.
     */
    private static ConfluenceClient buildConfluenceClient() {
        String confluenceBaseUrl = System.getenv("CONFLUENCE_BASE_URL");
        String confluenceSpaceKey = System.getenv("CONFLUENCE_SPACE_KEY");

        if (confluenceBaseUrl == null || confluenceBaseUrl.isBlank()) {
            log.info("[SchedulerHandler] CONFLUENCE_BASE_URL 미설정 → Confluence 비활성");
            return null;
        }
        if (confluenceSpaceKey == null || confluenceSpaceKey.isBlank()) {
            log.info("[SchedulerHandler] CONFLUENCE_SPACE_KEY 미설정 → Confluence 비활성");
            return null;
        }

        log.info("[SchedulerHandler] ConfluenceClient 초기화: url={}, space={}",
                confluenceBaseUrl, confluenceSpaceKey);
        return new ConfluenceClient(
                confluenceBaseUrl, confluenceSpaceKey,
                new BasicTokenProvider("JIRA_EMAIL", "JIRA_API_TOKEN"));
    }

    /**
     * 필수 환경변수 조회 — 미설정 시 ConfigException 발생.
     */
    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new ConfigException("필수 환경변수 미설정: " + name);
        }
        return v;
    }

    /**
     * 선택 환경변수 조회 — 미설정 시 defaultValue 반환.
     */
    private static String getEnvOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
