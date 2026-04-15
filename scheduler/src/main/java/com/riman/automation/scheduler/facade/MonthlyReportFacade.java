package com.riman.automation.scheduler.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.dto.report.MonthlyReportData.MonthlyTicketItem;
import com.riman.automation.scheduler.dto.s3.MonthlyReportConfig;
import com.riman.automation.scheduler.dto.s3.ProjectGroup;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import com.riman.automation.scheduler.service.ReportArchiveService;
import com.riman.automation.scheduler.service.collect.MonthlyCalendarTicketCollector;
import com.riman.automation.scheduler.service.collect.MonthlyCalendarTicketCollector.CollectResult;
import com.riman.automation.scheduler.service.load.TeamMemberService;
import com.riman.automation.scheduler.service.format.MonthlyReportFormatter;
import com.riman.automation.scheduler.service.report.MonthlyReportService;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 월간 실적 보고 오케스트레이터이다. 매월 첫 영업일에 전월을 결산하여 Confluence 페이지에 게시한다.
 * targetMonth 파라미터가 yyyy-MM 형식으로 전달되면 해당 월을, 미전달 시 baseDate 기준 이전 월을 사용한다.
 * scheduler-config.json의 monthlyReport 섹션에 project_groups가 있으면 그룹별로, 아니면 통합 페이지로 게시한다.
 */
@Slf4j
public class MonthlyReportFacade {

  private static final ObjectMapper OM = new ObjectMapper();

  private final S3Client s3Client;
  private final String configBucket;
  private final String configKey;
  private final TeamMemberService teamMemberService;
  private final MonthlyCalendarTicketCollector ticketCollector;
  private final MonthlyReportFormatter formatter;
  private final MonthlyReportService monthlyReportService;
  private final SlackClient slackClient;
  private final ReportArchiveService archiveService;

  public MonthlyReportFacade(
      S3Client s3Client,
      String configBucket,
      String configKey,
      TeamMemberService teamMemberService,
      MonthlyCalendarTicketCollector ticketCollector,
      MonthlyReportFormatter formatter,
      MonthlyReportService monthlyReportService,
      SlackClient slackClient,
      ReportArchiveService archiveService) {
    this.s3Client = s3Client;
    this.configBucket = configBucket;
    this.configKey = configKey;
    this.teamMemberService = teamMemberService;
    this.ticketCollector = ticketCollector;
    this.formatter = formatter;
    this.monthlyReportService = monthlyReportService;
    this.slackClient = slackClient;
    this.archiveService = archiveService;
  }

  /**
   * 월간보고를 실행한다. baseDate 기준 이전 월을 결산한다.
   *
   * @param baseDate    Lambda 실행일
   * @param targetMonth 대상 월 지정(예: "2026-01"). null이면 baseDate 기준 이전 월 자동 사용.
   */
  public void runMonthly(LocalDate baseDate, String targetMonth) {
    log.info("[MonthlyReportFacade] 월간보고 시작: baseDate={}, targetMonth={}",
        baseDate, targetMonth);

    MonthlyReportConfig config = loadConfig();

    if (Boolean.FALSE.equals(config.getEnabled())) {
      log.info("[MonthlyReportFacade] 월간보고 비활성화 (enabled=false)");
      return;
    }

    if (config.getTicketCalendarId() == null || config.getTicketCalendarId().isBlank()) {
      throw new ConfigException(
          "monthlyReport.ticket_calendar_id 미설정 — scheduler-config.json 확인 필요");
    }

    MonthlyReportData data = buildReportData(baseDate, targetMonth);
    log.info("[MonthlyReportFacade] 보고 대상: {}, 분기={}", data.pageMetaLabel(), data.quarterLabel());

    List<TeamMember> members = teamMemberService.loadEnabled();
    if (members.isEmpty()) {
      log.warn("[MonthlyReportFacade] 활성 팀원 없음, 종료");
      return;
    }
    log.info("[MonthlyReportFacade] 팀원: {}명", members.size());

    CollectResult collected = ticketCollector.collect(
        config.getTicketCalendarId(),
        members,
        data.getMonthStart(), data.getMonthEnd(),
        data.getQuarterStart(), data.getQuarterEnd());

    data.setDoneByCategory(collected.getDoneByCategory());
    data.setInProgressByCategory(collected.getInProgressByCategory());
    data.setIssuesByCategory(collected.getIssuesByCategory());

    if (config.isGroupSeparationEnabled()) {
      publishByProjectGroups(data, config);
    } else {
      publishUnified(data, config);
    }
  }

  private void publishUnified(MonthlyReportData data, MonthlyReportConfig config) {
    String pageHtml = formatter.format(data);
    try {
      String pageId = monthlyReportService.publishMonthlyPage(
          data, pageHtml,
          config.getConfluenceParentPageId(),
          config.getTeamName());

      String pageUrl = monthlyReportService.buildPageUrl(pageId);
      log.info("[MonthlyReportFacade] 월간보고 완료: url={}", pageUrl);

      String pageTitle = monthlyReportService.buildMonthlyTitle(data, config.getTeamName());
      monthlyReportService.attachExcel(pageId, pageTitle, data);

      if (archiveService != null) {
        try {
          String yearMonth = String.format("%d-%02d", data.getYear(), data.getMonth());
          archiveService.archiveMonthly(yearMonth, pageHtml);
        } catch (Exception e) {
          log.warn("[MonthlyReportFacade] monthly 아카이빙 실패 (무시): {}", e.getMessage());
        }
      }
    } catch (Exception e) {
      String monthlyTitle = monthlyReportService.buildMonthlyTitle(data, config.getTeamName());
      notifyConfluenceError("월간보고", monthlyTitle, e, config.getErrorNotifySlackUserId());
      throw e;
    }
  }

  private void publishByProjectGroups(MonthlyReportData data, MonthlyReportConfig config) {
    for (ProjectGroup group : config.getProjectGroups()) {
      try {
        MonthlyReportData groupData = filterDataByCategories(data, group.getEffectiveCategories());

        String pageHtml = formatter.format(groupData);
        String groupTeamName = config.getTeamName() + " - " + group.getName();

        String pageId = monthlyReportService.publishMonthlyPage(
            groupData, pageHtml,
            config.getConfluenceParentPageId(),
            groupTeamName);

        String pageUrl = monthlyReportService.buildPageUrl(pageId);
        log.info("[MonthlyReportFacade] 월간보고 그룹 완료: group={}, url={}",
            group.getName(), pageUrl);

        String pageTitle = monthlyReportService.buildMonthlyTitle(groupData, groupTeamName);
        monthlyReportService.attachExcel(pageId, pageTitle, groupData);

        if (archiveService != null) {
          try {
            String yearMonth = String.format("%d-%02d", data.getYear(), data.getMonth());
            String safeName = group.getName().replace("/", "_");
            archiveService.archiveMonthlyGroup(yearMonth, safeName, pageHtml);
          } catch (Exception e) {
            log.warn("[MonthlyReportFacade] monthly group 아카이빙 실패 (무시): group={}, err={}",
                group.getName(), e.getMessage());
          }
        }
      } catch (Exception e) {
        String title = monthlyReportService.buildMonthlyTitle(data,
            config.getTeamName() + " - " + group.getName());
        notifyConfluenceError("월간보고(" + group.getName() + ")", title,
            e, config.getErrorNotifySlackUserId());
        log.error("[MonthlyReportFacade] 월간보고 그룹 실패: group={}", group.getName(), e);
      }
    }
  }

  private MonthlyReportData filterDataByCategories(MonthlyReportData data, List<String> categories) {
    return MonthlyReportData.builder()
        .baseDate(data.getBaseDate())
        .monthStart(data.getMonthStart())
        .monthEnd(data.getMonthEnd())
        .year(data.getYear())
        .month(data.getMonth())
        .quarter(data.getQuarter())
        .quarterStart(data.getQuarterStart())
        .quarterEnd(data.getQuarterEnd())
        .doneByCategory(filterMap(data.getDoneByCategory(), categories))
        .inProgressByCategory(filterMap(data.getInProgressByCategory(), categories))
        .issuesByCategory(filterMap(data.getIssuesByCategory(), categories))
        .build();
  }

  private static Map<String, List<MonthlyTicketItem>> filterMap(
      Map<String, List<MonthlyTicketItem>> source, List<String> categories) {
    if (source == null) return Map.of();
    return source.entrySet().stream()
        .filter(e -> categories.contains(e.getKey()))
        .collect(Collectors.toMap(
            Map.Entry::getKey, Map.Entry::getValue,
            (a, b) -> a, LinkedHashMap::new));
  }

  private void notifyConfluenceError(String reportType, String pageTitle,
                                     Exception error, String slackUserId) {
    if (slackUserId == null || slackUserId.isBlank()) {
      log.warn("[MonthlyReportFacade] {} Confluence 실패 알림 스킵 — error_notify_slack_user_id 미설정",
          reportType);
      return;
    }
    try {
      String dmChannelId = slackClient.openDm(slackUserId);
      String errorMsg = error.getMessage();
      if (errorMsg != null && errorMsg.length() > 200) {
        errorMsg = errorMsg.substring(0, 200) + "...";
      }
      String payload = SlackBlockBuilder.forChannel(dmChannelId)
          .header(reportType + " Confluence 페이지 생성 실패")
          .section("*페이지:* " + pageTitle)
          .section("*오류:* " + errorMsg)
          .context("CloudWatch 로그를 확인해 주세요.")
          .fallbackText(reportType + " Confluence 실패: " + pageTitle)
          .build();
      slackClient.postMessage(payload);
      log.info("[MonthlyReportFacade] {} 실패 Slack 알림 발송 완료: userId={}", reportType, slackUserId);
    } catch (Exception slackError) {
      log.error("[MonthlyReportFacade] {} 실패 Slack 알림 발송 실패: {}",
          reportType, slackError.getMessage());
    }
  }

  /**
   * 대상 월과 분기 범위를 계산하여 MonthlyReportData를 빌드한다.
   *
   * @param baseDate    Lambda 실행일
   * @param targetMonth yyyy-MM 형식. null이면 baseDate 기준 이전 월 자동 사용.
   */
  private MonthlyReportData buildReportData(LocalDate baseDate, String targetMonth) {
    LocalDate targetDate = resolveTargetMonth(baseDate, targetMonth);

    int year = targetDate.getYear();
    int month = targetDate.getMonthValue();
    int quarter = (month - 1) / 3 + 1;

    LocalDate monthStart = LocalDate.of(year, month, 1);
    LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
    LocalDate qStart = quarterStart(year, quarter);
    LocalDate qEnd = quarterEnd(year, quarter);

    log.info("[MonthlyReportFacade] 날짜: monthStart={}, monthEnd={}, Q{}: {} ~ {}",
        monthStart, monthEnd, quarter, qStart, qEnd);

    return MonthlyReportData.builder()
        .baseDate(baseDate)
        .monthStart(monthStart)
        .monthEnd(monthEnd)
        .year(year)
        .month(month)
        .quarter(quarter)
        .quarterStart(qStart)
        .quarterEnd(qEnd)
        .build();
  }

  /**
   * 대상 월을 결정한다. targetMonth가 yyyy-MM 형식으로 유효하면 해당 월 1일을,
   * null이거나 파싱 실패 시 baseDate 기준 이전 월 1일을 반환한다.
   */
  private LocalDate resolveTargetMonth(LocalDate baseDate, String targetMonth) {
    if (targetMonth != null && !targetMonth.isBlank()) {
      try {
        LocalDate parsed = LocalDate.parse(targetMonth + "-01");
        log.info("[MonthlyReportFacade] targetMonth 파싱 성공: {}", parsed);
        return parsed;
      } catch (Exception e) {
        log.warn("[MonthlyReportFacade] targetMonth 파싱 실패 '{}', 이전 월 자동 사용: {}",
            targetMonth, e.getMessage());
      }
    }
    LocalDate prevMonth = baseDate.minusMonths(1).withDayOfMonth(1);
    log.info("[MonthlyReportFacade] targetMonth 미지정 → 이전 월 자동 사용: {}", prevMonth);
    return prevMonth;
  }

  private static LocalDate quarterStart(int year, int q) {
    return LocalDate.of(year, (q - 1) * 3 + 1, 1);
  }

  private static LocalDate quarterEnd(int year, int q) {
    return LocalDate.of(year, q * 3, 1).with(TemporalAdjusters.lastDayOfMonth());
  }

  private MonthlyReportConfig loadConfig() {
    try {
      log.info("[MonthlyReportFacade] 설정 로드: {}/{}", configBucket, configKey);
      byte[] bytes = s3Client.getObject(
          GetObjectRequest.builder().bucket(configBucket).key(configKey).build()
      ).readAllBytes();
      JsonNode root = OM.readTree(new String(bytes, StandardCharsets.UTF_8));
      JsonNode node = root.path("monthlyReport");
      if (node.isMissingNode() || node.isNull()) {
        throw new ConfigException(
            "scheduler-config.json 에 'monthlyReport' 섹션 없음: "
                + configBucket + "/" + configKey);
      }
      return OM.treeToValue(node, MonthlyReportConfig.class);
    } catch (ConfigException e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigException(
          "월간보고 설정 로드 실패: " + configBucket + "/" + configKey, e);
    }
  }
}
