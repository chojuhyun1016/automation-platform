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
import com.riman.automation.common.util.SentryInitializer;
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
import com.riman.automation.scheduler.service.ReportArchiveService;
import com.riman.automation.scheduler.service.excel.MonthlyExcelGenerator;
import com.riman.automation.scheduler.service.format.MonthlyReportFormatter;
import com.riman.automation.scheduler.service.report.MonthlyReportService;
import com.riman.automation.scheduler.service.excel.WeeklyExcelGenerator;
import com.riman.automation.scheduler.service.format.WeeklyReportFormatter;
import com.riman.automation.scheduler.service.report.WeeklyReportService;
import com.riman.automation.scheduler.dto.s3.ArchiveConfig;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.LocalDate;
import java.util.Map;

/**
 * EventBridge Scheduler의 Lambda 진입점이다.
 * event.report_type 값에 따라 daily/weekly/monthly 보고서 오케스트레이터를 분기한다.
 * 입력 타입을 Map으로 수신하여 EventBridge Scheduler가 보내는 빈 "time" 필드 파싱 오류를 회피한다.
 */
@Slf4j
public class SchedulerHandler implements RequestHandler<Map<String, Object>, String> {

  /**
   * 일일 보고서 오케스트레이터이다.
   */
  private static final DailyReportFacade dailyOrchestrator;

  /**
   * 주간 실적 보고서 오케스트레이터이다. CONFLUENCE_BASE_URL 미설정 시 null이다.
   */
  private static final WeeklyReportFacade weeklyOrchestrator;

  /**
   * 월간 실적 보고서 오케스트레이터이다. CONFLUENCE_BASE_URL 미설정 시 null이다.
   */
  private static final MonthlyReportFacade monthlyOrchestrator;

  static {
    log.info("[SchedulerHandler] Lambda cold start: 의존성 초기화");

    String configBucket = requireEnv("CONFIG_BUCKET");
    String schedulerConfigKey = getEnvOrDefault("SCHEDULER_CONFIG_KEY", "scheduler-config.json");
    String jiraBaseUrl = requireEnv("JIRA_BASE_URL");

    S3Client s3 = S3Client.builder()
        .region(Region.AP_NORTHEAST_2)
        .build();

    SlackClient slackClient = new SlackClient(
        new EnvTokenProvider("SLACK_REPORT_BOT_TOKEN"));

    JiraClient jiraClient = new JiraClient(
        jiraBaseUrl, new BasicTokenProvider("JIRA_EMAIL", "JIRA_API_TOKEN"));

    GoogleCalendarClient calendarClient = new GoogleCalendarClient(
        loadGoogleCredentials(s3));

    TeamMemberService teamMemberService = new TeamMemberService(s3, configBucket);

    DailyCalendarTicketCollector ticketCollector =
        new DailyCalendarTicketCollector(calendarClient, jiraBaseUrl, jiraClient);
    DailyAbsenceCollector absenceCollector = new DailyAbsenceCollector(calendarClient);
    DailyReportFormatter formatter = new DailyReportFormatter();

    DailyReportService aiRefiner = buildAiRefiner(formatter, s3, configBucket);

    DailyScheduleCollector scheduleCollector = buildScheduleCollector(calendarClient);

    ReportArchiveService archiveService = buildArchiveService(s3, configBucket, schedulerConfigKey);

    dailyOrchestrator = new DailyReportFacade(
        s3, configBucket, schedulerConfigKey,
        teamMemberService,
        ticketCollector, absenceCollector,
        scheduleCollector,
        formatter, aiRefiner,
        slackClient, archiveService);

    ConfluenceClient sharedConfluenceClient = buildConfluenceClient();

    weeklyOrchestrator = buildWeeklyOrchestrator(
        s3, configBucket, schedulerConfigKey,
        calendarClient, jiraClient, jiraBaseUrl,
        teamMemberService, sharedConfluenceClient, slackClient, archiveService);

    monthlyOrchestrator = buildMonthlyOrchestrator(
        s3, configBucket, schedulerConfigKey,
        calendarClient, jiraClient, jiraBaseUrl,
        teamMemberService, sharedConfluenceClient, slackClient, archiveService);

    SentryInitializer.init("scheduler");
    log.info("[SchedulerHandler] 초기화 완료 (AI={}, schedule={}, weekly={}, monthly={}, configKey={})",
        aiRefiner != null ? "활성" : "비활성",
        scheduleCollector != null ? "활성" : "비활성",
        weeklyOrchestrator != null ? "활성" : "비활성",
        monthlyOrchestrator != null ? "활성" : "비활성",
        schedulerConfigKey);
  }

  @Override
  public String handleRequest(Map<String, Object> event, Context context) {
    String source = (event != null)
        ? String.valueOf(event.getOrDefault("source", "unknown"))
        : "unknown";
    log.info("[SchedulerHandler] 이벤트 수신: source={}", source);

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
      SentryInitializer.captureException(e, "handleRequest");
      SentryInitializer.flush();
      return "CONFIG_ERROR: " + e.getMessage();
    } catch (AutomationException e) {
      log.error("[SchedulerHandler] 자동화 오류: {} {}", e.getErrorCode(), e.getMessage());
      SentryInitializer.captureException(e, "handleRequest");
      SentryInitializer.flush();
      return "ERROR: " + e.getErrorCode() + " " + e.getMessage();
    } catch (Exception e) {
      log.error("[SchedulerHandler] 예상치 못한 오류", e);
      SentryInitializer.captureException(e, "handleRequest");
      SentryInitializer.flush();
      return "UNEXPECTED_ERROR: " + e.getMessage();
    }
  }

  /**
   * 일일 보고서를 실행한다. time 미지정 시 KST 오늘 날짜를 사용한다.
   */
  private String runDaily(String timeValue) {
    LocalDate baseDate = parseDateOrToday(timeValue);
    log.info("[SchedulerHandler] 일일보고 실행: baseDate={}", baseDate);
    dailyOrchestrator.runDaily(baseDate);
    return "SUCCESS";
  }

  /**
   * 주간 실적 보고서를 실행한다. time 미지정 시 KST 오늘 기준 전주를 사용한다.
   * CONFLUENCE 환경변수 미설정 시 스킵한다.
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
   * 월간 실적 보고서를 실행한다. time 미지정 시 KST 오늘 기준 이전 월을 사용한다.
   * CONFLUENCE 환경변수 미설정 시 스킵한다.
   */
  private String runMonthly(String timeValue) {
    if (monthlyOrchestrator == null) {
      log.warn("[SchedulerHandler] 월간보고 비활성 — monthlyOrchestrator 미초기화");
      return "SKIPPED: monthlyOrchestrator not initialized";
    }

    String targetMonth = parseYearMonthOrNull(timeValue);

    LocalDate baseDate = DateTimeUtil.todayKst();

    log.info("[SchedulerHandler] 월간보고 실행: baseDate={}, targetMonth={}",
        baseDate, targetMonth != null ? targetMonth : "(자동: 이전 월)");
    monthlyOrchestrator.runMonthly(baseDate, targetMonth);
    return "SUCCESS";
  }

  /**
   * event.time 필드를 문자열로 추출한다. 빈 문자열은 null로 정규화한다.
   */
  private static String extractTimeValue(Map<String, Object> event) {
    if (event == null) return null;
    Object val = event.get("time");
    if (!(val instanceof String str)) return null;
    return str.isBlank() ? null : str.trim();
  }

  /**
   * time 문자열을 yyyy-MM-dd 형식으로 파싱하여 LocalDate를 반환한다.
   * null 또는 파싱 실패 시 KST 오늘 날짜를 반환한다. daily/weekly에서 사용한다.
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
   * time 문자열을 yyyy-MM 형식으로 파싱하여 반환한다.
   * null 또는 파싱 실패 시 null을 반환하며 MonthlyReportFacade가 이전 월을 자동 처리한다.
   */
  private static String parseYearMonthOrNull(String timeValue) {
    if (timeValue == null) return null;
    try {
      String yearMonth = timeValue.length() >= 7 ? timeValue.substring(0, 7) : timeValue;
      LocalDate.parse(yearMonth + "-01");
      log.info("[SchedulerHandler] targetMonth from time: {}", yearMonth);
      return yearMonth;
    } catch (Exception e) {
      log.warn("[SchedulerHandler] time 월 파싱 실패 '{}', 이전 월 자동 사용", timeValue);
      return null;
    }
  }

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
   * AI 보고서 다듬기 서비스를 빌드한다.
   * ANTHROPIC_API_KEY 미설정 시 null을 반환하며 DailyReportFacade가 원본 포맷을 사용한다.
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
   * 오늘 일정 수집 서비스를 빌드한다.
   * SCHEDULE_MAPPING_TABLE 미설정 시 null을 반환하며 오늘 일정 섹션을 생략한다.
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
   * 주간 실적 보고 오케스트레이터를 빌드한다.
   * ConfluenceClient가 null이면 null을 반환하며 월간 오케스트레이터와 동일 인스턴스를 공유한다.
   */
  private static WeeklyReportFacade buildWeeklyOrchestrator(
      S3Client s3, String configBucket, String schedulerConfigKey,
      GoogleCalendarClient calendarClient,
      JiraClient jiraClient, String jiraBaseUrl,
      TeamMemberService teamMemberService,
      ConfluenceClient confluenceClient,
      SlackClient slackClient,
      ReportArchiveService archiveService) {

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
        weeklyFormatter, weeklyReportService, slackClient, archiveService);
  }

  /**
   * 월간 실적 보고 오케스트레이터를 빌드한다.
   * ConfluenceClient가 null이면 null을 반환하며 주간 오케스트레이터와 동일 인스턴스를 공유한다.
   */
  private static MonthlyReportFacade buildMonthlyOrchestrator(
      S3Client s3, String configBucket, String schedulerConfigKey,
      GoogleCalendarClient calendarClient,
      JiraClient jiraClient, String jiraBaseUrl,
      TeamMemberService teamMemberService,
      ConfluenceClient confluenceClient,
      SlackClient slackClient,
      ReportArchiveService archiveService) {

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
        monthlyFormatter, monthlyReportService, slackClient, archiveService);
  }

  /**
   * 보고서 아카이빙 서비스를 빌드한다.
   * scheduler-config.json archive 섹션이 enabled=true일 때만 활성화한다.
   * 설정 로드 실패 시 null을 반환하여 아카이빙을 비활성화한다.
   */
  private static ReportArchiveService buildArchiveService(
      S3Client s3, String configBucket, String schedulerConfigKey) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
      byte[] bytes = s3.getObject(
          GetObjectRequest.builder().bucket(configBucket).key(schedulerConfigKey).build()
      ).readAllBytes();
      com.fasterxml.jackson.databind.JsonNode root = om.readTree(
          new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      com.fasterxml.jackson.databind.JsonNode archiveNode = root.path("archive");
      if (archiveNode.isMissingNode() || archiveNode.isNull()) {
        log.info("[SchedulerHandler] archive 설정 없음 → 아카이빙 비활성");
        return null;
      }
      ArchiveConfig archiveConfig = om.treeToValue(archiveNode, ArchiveConfig.class);
      if (!archiveConfig.isEnabled()) {
        log.info("[SchedulerHandler] archive.enabled=false → 아카이빙 비활성");
        return null;
      }
      log.info("[SchedulerHandler] ReportArchiveService 초기화: prefix={}", archiveConfig.getPrefix());
      return new ReportArchiveService(s3, configBucket, archiveConfig.getPrefix());
    } catch (Exception e) {
      log.warn("[SchedulerHandler] archive 설정 로드 실패, 아카이빙 비활성: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Confluence 클라이언트를 빌드한다.
   * CONFLUENCE_BASE_URL 또는 CONFLUENCE_SPACE_KEY 미설정 시 null을 반환한다.
   * 주간과 월간이 동일 설정을 사용하므로 단일 인스턴스로 HTTP 커넥션 풀 중복을 방지한다.
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
   * 필수 환경변수를 조회한다. 미설정 시 ConfigException을 발생시킨다.
   */
  private static String requireEnv(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      throw new ConfigException("필수 환경변수 미설정: " + name);
    }
    return v;
  }

  /**
   * 선택 환경변수를 조회한다. 미설정 시 defaultValue를 반환한다.
   */
  private static String getEnvOrDefault(String name, String defaultValue) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }
}
