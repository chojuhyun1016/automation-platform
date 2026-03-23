package com.riman.automation.scheduler.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import com.riman.automation.scheduler.dto.s3.WeeklyReportConfig;
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
import java.util.List;

/**
 * 주간(실적) 보고 오케스트레이터
 *
 * <p><b>실행 시점:</b> 매주 월요일 오전 (전주 결산)
 *
 * <p><b>DailyReportFacade 와의 대응:</b>
 * <pre>
 *   DailyReportFacade   — 일일 보고서 (Slack DM 전송)
 *   WeeklyReportFacade  — 주간 실적 보고서 (Confluence 페이지)
 * </pre>
 *
 * <p><b>흐름:</b>
 * <pre>
 *   1. S3 에서 weeklyReport 설정 로드
 *   2. 전주 날짜 범위 + 분기 범위 계산
 *   3. TeamMemberService → 활성 팀원 목록 로드 (캘린더 담당자 매칭 기준)
 *   4. WeeklyCalendarTicketCollector → 캘린더 기반 티켓 수집 (완료 / 진행중 / 이슈)
 *   5. WeeklyReportFormatter → Confluence Storage Format HTML 생성
 *   6. WeeklyReportService   → 연도/분기/월 계층 확보 + 페이지 생성/업데이트
 * </pre>
 *
 * <p><b>캘린더 기반 수집 배경:</b>
 * Jira 직접 조회 시 티켓 담당자가 타팀원으로 변경되면 우리 팀원의 작업 이력이 누락된다.
 * Google Calendar는 팀원이 담당했던 이벤트 이력이 그대로 남으므로 실적 집계에 정확하다.
 *
 * <p>Slack DM / 엑셀 첨부는 {@link WeeklyReportService}에 메서드 추가 후 이 클래스에서 호출한다.
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

    public WeeklyReportFacade(
            S3Client s3Client,
            String configBucket,
            String configKey,
            TeamMemberService teamMemberService,
            WeeklyCalendarTicketCollector ticketCollector,
            WeeklyReportFormatter formatter,
            WeeklyReportService weeklyReportService) {
        this.s3Client = s3Client;
        this.configBucket = configBucket;
        this.configKey = configKey;
        this.teamMemberService = teamMemberService;
        this.ticketCollector = ticketCollector;
        this.formatter = formatter;
        this.weeklyReportService = weeklyReportService;
    }

    // =========================================================================
    // 실행 진입점
    // =========================================================================

    /**
     * 주간보고 실행 — baseDate 기준 전주 결산.
     *
     * @param baseDate Lambda 실행일 (보통 월요일)
     */
    public void runWeekly(LocalDate baseDate) {
        log.info("[WeeklyReportFacade] 주간보고 시작: baseDate={}", baseDate);

        // ── 1) 설정 로드 ──────────────────────────────────────────────────────
        WeeklyReportConfig config = loadConfig();

        if (Boolean.FALSE.equals(config.getEnabled())) {
            log.info("[WeeklyReportFacade] 주간보고 비활성화 (enabled=false)");
            return;
        }

        if (config.getTicketCalendarId() == null || config.getTicketCalendarId().isBlank()) {
            throw new ConfigException(
                    "weeklyReport.ticket_calendar_id 미설정 — scheduler-config.json 확인 필요");
        }

        // ── 2) 날짜 범위 계산 ────────────────────────────────────────────────
        WeeklyReportData data = buildReportData(baseDate);
        log.info("[WeeklyReportFacade] 보고 대상: {}, 분기={}", data.pageTitle(), data.quarterLabel());

        // ── 3) 팀원 목록 로드 ────────────────────────────────────────────────
        List<TeamMember> members = teamMemberService.loadEnabled();
        if (members.isEmpty()) {
            log.warn("[WeeklyReportFacade] 활성 팀원 없음, 종료");
            return;
        }
        log.info("[WeeklyReportFacade] 팀원: {}명", members.size());

        // ── 4) 캘린더 기반 티켓 수집 ─────────────────────────────────────────
        CollectResult collected = ticketCollector.collect(
                config.getTicketCalendarId(),
                members,
                data.getWeekStart(), data.getWeekEnd(),
                data.getQuarterStart(), data.getQuarterEnd());

        data.setDoneByCategory(collected.getDoneByCategory());
        data.setInProgressByCategory(collected.getInProgressByCategory());
        data.setIssuesByCategory(collected.getIssuesByCategory());

        // ── 5) HTML 생성 ─────────────────────────────────────────────────────
        String pageHtml = formatter.format(data);

        // ── 6) Confluence 페이지 생성/업데이트 ──────────────────────────────
        String pageId = weeklyReportService.publishWeeklyPage(
                data, pageHtml,
                config.getConfluenceParentPageId(),
                config.getTeamName());

        String pageUrl = weeklyReportService.buildPageUrl(pageId);
        log.info("[WeeklyReportFacade] 주간보고 완료: url={}", pageUrl);

        // 7) 엑셀 생성 + 페이지 첨부
        String pageTitle = weeklyReportService.buildWeeklyTitle(data, config.getTeamName());
        weeklyReportService.attachExcel(pageId, pageTitle, data);

        // TODO: 8) Slack DM 발송  weeklyReportService.notifySlack(pageUrl, ...)
    }

    // =========================================================================
    // 날짜 / 분기 계산
    // =========================================================================

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

    // =========================================================================
    // 설정 로드
    // =========================================================================

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
