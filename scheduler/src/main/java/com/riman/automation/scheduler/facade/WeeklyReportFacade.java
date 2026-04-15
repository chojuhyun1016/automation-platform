package com.riman.automation.scheduler.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.dto.report.WeeklyReportData.WeeklyTicketItem;
import com.riman.automation.scheduler.dto.s3.ProjectGroup;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import com.riman.automation.scheduler.dto.s3.WeeklyReportConfig;
import com.riman.automation.scheduler.service.ReportArchiveService;
import com.riman.automation.scheduler.service.collect.WeeklyCalendarTicketCollector;
import com.riman.automation.scheduler.service.collect.WeeklyCalendarTicketCollector.CollectResult;
import com.riman.automation.scheduler.service.load.TeamMemberService;
import com.riman.automation.scheduler.service.format.WeeklyReportFormatter;
import com.riman.automation.scheduler.service.report.WeeklyReportService;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 주간 실적 보고 오케스트레이터이다. 매주 월요일 오전에 전주를 결산하여 Confluence 페이지에 게시한다.
 * 캘린더 기반 수집은 Jira 담당자 변경 시 실적이 누락되는 문제를 회피하기 위한 선택이다.
 * scheduler-config.json의 weeklyReport 섹션에 project_groups가 있으면 그룹별로, 아니면 통합 페이지로 게시한다.
 */
@Slf4j
public class WeeklyReportFacade {

  private static final ObjectMapper OM = new ObjectMapper();

  private final S3Client s3Client;
  private final String configBucket;
  private final String configKey;
  private final TeamMemberService teamMemberService;
  private final WeeklyCalendarTicketCollector ticketCollector;
  private final WeeklyReportFormatter formatter;
  private final WeeklyReportService weeklyReportService;
  private final SlackClient slackClient;
  private final ReportArchiveService archiveService;

  public WeeklyReportFacade(
      S3Client s3Client,
      String configBucket,
      String configKey,
      TeamMemberService teamMemberService,
      WeeklyCalendarTicketCollector ticketCollector,
      WeeklyReportFormatter formatter,
      WeeklyReportService weeklyReportService,
      SlackClient slackClient,
      ReportArchiveService archiveService) {
    this.s3Client = s3Client;
    this.configBucket = configBucket;
    this.configKey = configKey;
    this.teamMemberService = teamMemberService;
    this.ticketCollector = ticketCollector;
    this.formatter = formatter;
    this.weeklyReportService = weeklyReportService;
    this.slackClient = slackClient;
    this.archiveService = archiveService;
  }

  /**
   * 주간보고를 실행한다. baseDate 기준 전주(월~일)를 결산한다.
   *
   * @param baseDate Lambda 실행일 (보통 월요일)
   */
  public void runWeekly(LocalDate baseDate) {
    log.info("[WeeklyReportFacade] 주간보고 시작: baseDate={}", baseDate);

    WeeklyReportConfig config = loadConfig();

    if (Boolean.FALSE.equals(config.getEnabled())) {
      log.info("[WeeklyReportFacade] 주간보고 비활성화 (enabled=false)");
      return;
    }

    if (config.getTicketCalendarId() == null || config.getTicketCalendarId().isBlank()) {
      throw new ConfigException(
          "weeklyReport.ticket_calendar_id 미설정 — scheduler-config.json 확인 필요");
    }

    WeeklyReportData data = buildReportData(baseDate);
    log.info("[WeeklyReportFacade] 보고 대상: {}, 분기={}", data.pageTitle(), data.quarterLabel());

    List<TeamMember> members = teamMemberService.loadEnabled();
    if (members.isEmpty()) {
      log.warn("[WeeklyReportFacade] 활성 팀원 없음, 종료");
      return;
    }
    log.info("[WeeklyReportFacade] 팀원: {}명", members.size());

    CollectResult collected = ticketCollector.collect(
        config.getTicketCalendarId(),
        members,
        data.getWeekStart(), data.getWeekEnd(),
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

  private void publishUnified(WeeklyReportData data, WeeklyReportConfig config) {
    String pageHtml = formatter.format(data);
    try {
      String pageId = weeklyReportService.publishWeeklyPage(
          data, pageHtml,
          config.getConfluenceParentPageId(),
          config.getTeamName());

      String pageUrl = weeklyReportService.buildPageUrl(pageId);
      log.info("[WeeklyReportFacade] 주간보고 완료: url={}", pageUrl);

      String pageTitle = weeklyReportService.buildWeeklyTitle(data, config.getTeamName());
      weeklyReportService.attachExcel(pageId, pageTitle, data);

      if (archiveService != null) {
        try {
          archiveService.archiveWeekly(data.getWeekStart(), pageHtml);
        } catch (Exception e) {
          log.warn("[WeeklyReportFacade] weekly 아카이빙 실패 (무시): {}", e.getMessage());
        }
      }
    } catch (Exception e) {
      String weeklyTitle = weeklyReportService.buildWeeklyTitle(data, config.getTeamName());
      notifyConfluenceError("주간보고", weeklyTitle, e, config.getErrorNotifySlackUserId());
      throw e;
    }
  }

  private void publishByProjectGroups(WeeklyReportData data, WeeklyReportConfig config) {
    for (ProjectGroup group : config.getProjectGroups()) {
      try {
        WeeklyReportData groupData = filterDataByCategories(data, group.getEffectiveCategories());

        String pageHtml = formatter.format(groupData);
        String groupTeamName = config.getTeamName() + " - " + group.getName();

        String pageId = weeklyReportService.publishWeeklyPage(
            groupData, pageHtml,
            config.getConfluenceParentPageId(),
            groupTeamName);

        String pageUrl = weeklyReportService.buildPageUrl(pageId);
        log.info("[WeeklyReportFacade] 주간보고 그룹 완료: group={}, url={}",
            group.getName(), pageUrl);

        String pageTitle = weeklyReportService.buildWeeklyTitle(groupData, groupTeamName);
        weeklyReportService.attachExcel(pageId, pageTitle, groupData);

        if (archiveService != null) {
          try {
            String safeName = group.getName().replace("/", "_");
            archiveService.archiveWeeklyGroup(data.getWeekStart(), safeName, pageHtml);
          } catch (Exception e) {
            log.warn("[WeeklyReportFacade] weekly group 아카이빙 실패 (무시): group={}, err={}",
                group.getName(), e.getMessage());
          }
        }
      } catch (Exception e) {
        String title = weeklyReportService.buildWeeklyTitle(data,
            config.getTeamName() + " - " + group.getName());
        notifyConfluenceError("주간보고(" + group.getName() + ")", title,
            e, config.getErrorNotifySlackUserId());
        log.error("[WeeklyReportFacade] 주간보고 그룹 실패: group={}", group.getName(), e);
      }
    }
  }

  /**
   * 전체 데이터에서 특정 카테고리만 필터링한 새 WeeklyReportData를 생성한다.
   */
  private WeeklyReportData filterDataByCategories(WeeklyReportData data, List<String> categories) {
    return WeeklyReportData.builder()
        .baseDate(data.getBaseDate())
        .weekStart(data.getWeekStart())
        .weekEnd(data.getWeekEnd())
        .weekNumber(data.getWeekNumber())
        .year(data.getYear())
        .quarter(data.getQuarter())
        .quarterStart(data.getQuarterStart())
        .quarterEnd(data.getQuarterEnd())
        .doneByCategory(filterMap(data.getDoneByCategory(), categories))
        .inProgressByCategory(filterMap(data.getInProgressByCategory(), categories))
        .issuesByCategory(filterMap(data.getIssuesByCategory(), categories))
        .build();
  }

  private static Map<String, List<WeeklyTicketItem>> filterMap(
      Map<String, List<WeeklyTicketItem>> source, List<String> categories) {
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
      log.warn("[WeeklyReportFacade] {} Confluence 실패 알림 스킵 — error_notify_slack_user_id 미설정",
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
      log.info("[WeeklyReportFacade] {} 실패 Slack 알림 발송 완료: userId={}", reportType, slackUserId);
    } catch (Exception slackError) {
      log.error("[WeeklyReportFacade] {} 실패 Slack 알림 발송 실패: {}",
          reportType, slackError.getMessage());
    }
  }

  private WeeklyReportData buildReportData(LocalDate baseDate) {
    LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate lastMonday = thisMonday.minusWeeks(1);
    LocalDate lastSunday = lastMonday.plusDays(6);

    int year = lastMonday.getYear();
    int weekNumber = (int) lastMonday.getLong(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    int quarter = (lastMonday.getMonthValue() - 1) / 3 + 1;
    LocalDate qStart = quarterStart(year, quarter);
    LocalDate qEnd = quarterEnd(year, quarter);

    log.info("[WeeklyReportFacade] 날짜: weekStart={}, weekEnd={}, Q{}: {} ~ {}",
        lastMonday, lastSunday, quarter, qStart, qEnd);

    return WeeklyReportData.builder()
        .baseDate(baseDate)
        .weekStart(lastMonday)
        .weekEnd(lastSunday)
        .weekNumber(weekNumber)
        .year(year)
        .quarter(quarter)
        .quarterStart(qStart)
        .quarterEnd(qEnd)
        .build();
  }

  private static LocalDate quarterStart(int year, int q) {
    return LocalDate.of(year, (q - 1) * 3 + 1, 1);
  }

  private static LocalDate quarterEnd(int year, int q) {
    return LocalDate.of(year, q * 3, 1).with(TemporalAdjusters.lastDayOfMonth());
  }

  private WeeklyReportConfig loadConfig() {
    try {
      log.info("[WeeklyReportFacade] 설정 로드: {}/{}", configBucket, configKey);
      byte[] bytes = s3Client.getObject(
          GetObjectRequest.builder().bucket(configBucket).key(configKey).build()
      ).readAllBytes();
      JsonNode root = OM.readTree(new String(bytes, StandardCharsets.UTF_8));
      JsonNode node = root.path("weeklyReport");
      if (node.isMissingNode() || node.isNull()) {
        throw new ConfigException(
            "scheduler-config.json 에 'weeklyReport' 섹션 없음: "
                + configBucket + "/" + configKey);
      }
      return OM.treeToValue(node, WeeklyReportConfig.class);
    } catch (ConfigException e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigException(
          "주간보고 설정 로드 실패: " + configBucket + "/" + configKey, e);
    }
  }
}
