package com.riman.automation.scheduler.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.scheduler.dto.report.MonthlyReportData;
import com.riman.automation.scheduler.dto.s3.MonthlyReportConfig;
import com.riman.automation.scheduler.dto.s3.TeamMember;
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
import java.util.List;

/**
 * 월간(실적) 보고 오케스트레이터
 *
 * <p><b>실행 시점:</b> 매월 첫 번째 영업일 오전 (전월 결산)
 *
 * <p><b>DailyReportFacade / WeeklyReportFacade 와의 대응:</b>
 * <pre>
 *   DailyReportFacade   — 일일 보고서 (Slack DM)
 *   WeeklyReportFacade  — 주간 실적 보고서 (Confluence 페이지, 분기-주 계층)
 *   MonthlyReportFacade — 월간 실적 보고서 (Confluence 페이지, 분기-월 계층)  (이 클래스)
 * </pre>
 *
 * <p><b>흐름:</b>
 * <pre>
 *   1. S3 에서 monthlyReport 설정 로드
 *   2. 대상 월 날짜 범위 + 분기 범위 계산
 *   3. TeamMemberService → 활성 팀원 목록 로드
 *   4. MonthlyCalendarTicketCollector → 캘린더 기반 티켓 수집 (완료 / 진행중 / 이슈)
 *   5. MonthlyReportFormatter → Confluence Storage Format HTML 생성
 *   6. MonthlyReportService   → 연도/분기 계층 확보 + 페이지 생성/업데이트
 *   7. 엑셀 생성 + 페이지 첨부
 * </pre>
 *
 * <p><b>대상 월 결정:</b>
 * <ul>
 *   <li>{@code event.target_month} 파라미터가 "2026-01" 형식으로 전달되면 해당 월을 사용</li>
 *   <li>미전달 또는 파싱 실패 시 {@code baseDate} 기준 이전 월을 자동 사용</li>
 * </ul>
 *
 * <p><b>Confluence 페이지 계층:</b>
 * <pre>
 *   실적보고 (confluenceParentPageId)
 *     └─ 2026년 월간
 *         └─ 2026년 월간 Q1
 *             └─ 2026 Q1 1월 - 보상코어 개발팀 실적
 * </pre>
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

    public MonthlyReportFacade(
            S3Client s3Client,
            String configBucket,
            String configKey,
            TeamMemberService teamMemberService,
            MonthlyCalendarTicketCollector ticketCollector,
            MonthlyReportFormatter formatter,
            MonthlyReportService monthlyReportService) {
        this.s3Client = s3Client;
        this.configBucket = configBucket;
        this.configKey = configKey;
        this.teamMemberService = teamMemberService;
        this.ticketCollector = ticketCollector;
        this.formatter = formatter;
        this.monthlyReportService = monthlyReportService;
    }

    // =========================================================================
    // 실행 진입점
    // =========================================================================

    /**
     * 월간보고 실행 — baseDate 기준 이전 월 결산.
     *
     * @param baseDate    Lambda 실행일
     * @param targetMonth 대상 월 지정 (예: "2026-01"). null 이면 baseDate 기준 이전 월 자동 사용
     */
    public void runMonthly(LocalDate baseDate, String targetMonth) {
        log.info("[MonthlyReportFacade] 월간보고 시작: baseDate={}, targetMonth={}",
                baseDate, targetMonth);

        // ── 1) 설정 로드 ──────────────────────────────────────────────────────
        MonthlyReportConfig config = loadConfig();

        if (Boolean.FALSE.equals(config.getEnabled())) {
            log.info("[MonthlyReportFacade] 월간보고 비활성화 (enabled=false)");
            return;
        }

        if (config.getTicketCalendarId() == null || config.getTicketCalendarId().isBlank()) {
            throw new ConfigException(
                    "monthlyReport.ticket_calendar_id 미설정 — scheduler-config.json 확인 필요");
        }

        // ── 2) 대상 월 + 날짜 범위 계산 ─────────────────────────────────────
        MonthlyReportData data = buildReportData(baseDate, targetMonth);
        log.info("[MonthlyReportFacade] 보고 대상: {}, 분기={}", data.pageMetaLabel(), data.quarterLabel());

        // ── 3) 팀원 목록 로드 ────────────────────────────────────────────────
        List<TeamMember> members = teamMemberService.loadEnabled();
        if (members.isEmpty()) {
            log.warn("[MonthlyReportFacade] 활성 팀원 없음, 종료");
            return;
        }
        log.info("[MonthlyReportFacade] 팀원: {}명", members.size());

        // ── 4) 캘린더 기반 티켓 수집 ─────────────────────────────────────────
        CollectResult collected = ticketCollector.collect(
                config.getTicketCalendarId(),
                members,
                data.getMonthStart(), data.getMonthEnd(),
                data.getQuarterStart(), data.getQuarterEnd());

        data.setDoneByCategory(collected.getDoneByCategory());
        data.setInProgressByCategory(collected.getInProgressByCategory());
        data.setIssuesByCategory(collected.getIssuesByCategory());

        // ── 5) HTML 생성 ─────────────────────────────────────────────────────
        String pageHtml = formatter.format(data);

        // ── 6) Confluence 페이지 생성/업데이트 ──────────────────────────────
        String pageId = monthlyReportService.publishMonthlyPage(
                data, pageHtml,
                config.getConfluenceParentPageId(),
                config.getTeamName());

        String pageUrl = monthlyReportService.buildPageUrl(pageId);
        log.info("[MonthlyReportFacade] 월간보고 완료: url={}", pageUrl);

        // ── 7) 엑셀 생성 + 페이지 첨부 ──────────────────────────────────────
        String pageTitle = monthlyReportService.buildMonthlyTitle(data, config.getTeamName());
        monthlyReportService.attachExcel(pageId, pageTitle, data);

        // TODO: 8) Slack DM 발송
    }

    // =========================================================================
    // 날짜 / 분기 계산
    // =========================================================================

    /**
     * 대상 월 + 분기 범위를 계산하여 MonthlyReportData 빌드.
     *
     * @param baseDate    Lambda 실행일
     * @param targetMonth "yyyy-MM" 형식. null 이면 baseDate 이전 월 자동 사용
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
     * 대상 월 결정.
     *
     * <ul>
     *   <li>targetMonth가 "yyyy-MM" 형식으로 유효하면 해당 월의 1일을 반환</li>
     *   <li>null 이거나 파싱 실패 시 baseDate 기준 이전 월의 1일을 반환</li>
     * </ul>
     */
    private LocalDate resolveTargetMonth(LocalDate baseDate, String targetMonth) {
        if (targetMonth != null && !targetMonth.isBlank()) {
            try {
                // "yyyy-MM" → "yyyy-MM-01" 로 파싱
                LocalDate parsed = LocalDate.parse(targetMonth + "-01");
                log.info("[MonthlyReportFacade] targetMonth 파싱 성공: {}", parsed);
                return parsed;
            } catch (Exception e) {
                log.warn("[MonthlyReportFacade] targetMonth 파싱 실패 '{}', 이전 월 자동 사용: {}",
                        targetMonth, e.getMessage());
            }
        }
        // 기본: baseDate 이전 월
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

    // =========================================================================
    // 설정 로드
    // =========================================================================

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
