package com.riman.automation.scheduler.dto.report;

import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 월간보고 데이터 컨테이너
 *
 * <p><b>WeeklyReportData 와의 대응:</b>
 * <pre>
 *   WeeklyReportData — 주간 실적 (전주 완료 + 분기 진행중)
 *   MonthlyReportData — 월간 실적 (대상 월 완료 + 분기 진행중)
 * </pre>
 *
 * <p><b>보고 기간:</b>
 * 대상 월의 1일(monthStart) ~ 말일(monthEnd).
 * 예: 1월 → 2026-01-01 ~ 2026-01-31
 *
 * <p><b>카테고리 분류 기준:</b>
 * {@link WeeklyReportData#detectCategory(String, String)} 와 동일.
 * MonthlyReportData는 WeeklyReportData의 static 유틸을 위임하여 일관성을 유지한다.
 *
 * <p><b>페이지 계층:</b>
 * <pre>
 *   실적보고 (rootParentPageId)
 *     └─ 2026년 월간
 *         └─ 2026년 월간 Q1
 *             └─ 2026 Q1 1월 - 보상코어 개발팀 실적
 * </pre>
 */
@Data
@Builder
public class MonthlyReportData {

    // =========================================================================
    // 메타
    // =========================================================================

    /**
     * 보고서 기준일 (Lambda 실행일)
     */
    private LocalDate baseDate;

    /**
     * 대상 월 시작일 (1일)
     * 예: 2026-01-01
     */
    private LocalDate monthStart;

    /**
     * 대상 월 종료일 (말일)
     * 예: 2026-01-31
     */
    private LocalDate monthEnd;

    /**
     * 연도 (예: 2026)
     */
    private int year;

    /**
     * 대상 월 (1~12)
     */
    private int month;

    /**
     * 분기 (1~4)
     */
    private int quarter;

    /**
     * 분기 시작일 (예: 2026-01-01)
     */
    private LocalDate quarterStart;

    /**
     * 분기 종료일 (예: 2026-03-31)
     */
    private LocalDate quarterEnd;

    // =========================================================================
    // 티켓 데이터 — 카테고리별 분류
    // key: 카테고리명 (주문/회원/수당/포인트/ABO/RBO)
    // =========================================================================

    /**
     * 카테고리 → 대상 월 완료 티켓
     */
    private Map<String, List<MonthlyTicketItem>> doneByCategory;

    /**
     * 카테고리 → 분기 전체 진행중 티켓
     */
    private Map<String, List<MonthlyTicketItem>> inProgressByCategory;

    /**
     * 카테고리 → 이슈 티켓 ([이슈] 태그 + 미완료)
     */
    private Map<String, List<MonthlyTicketItem>> issuesByCategory;

    // =========================================================================
    // 카테고리 상수 — WeeklyReportData와 동일 순서 유지
    // =========================================================================

    /**
     * 카테고리 표시 순서 (Confluence 섹션 순서와 일치)
     */
    public static final List<String> CATEGORY_ORDER =
            List.of("주문", "회원", "수당", "포인트", "ABO", "RBO");

    // =========================================================================
    // 카테고리 분류 (static) — WeeklyReportData에 위임
    // =========================================================================

    /**
     * 프로젝트 키 + 제목으로 카테고리 반환.
     * WeeklyReportData.detectCategory 와 동일한 규칙 적용.
     *
     * @param projectKey 티켓 프로젝트 키 (예: "CCE", "RBO")
     * @param summary    티켓 제목
     * @return 카테고리명, 분류 불가면 null
     */
    public static String detectCategory(String projectKey, String summary) {
        return WeeklyReportData.detectCategory(projectKey, summary);
    }

    /**
     * 이슈 여부 — 제목에 {@code "[이슈]"} 포함.
     */
    public static boolean detectIssue(String summary) {
        return WeeklyReportData.detectIssue(summary);
    }

    // =========================================================================
    // 표시용 유틸
    // =========================================================================

    private static final DateTimeFormatter LABEL_FMT =
            DateTimeFormatter.ofPattern("MM-dd");

    /**
     * 기간 문자열. 예: "01-01 ~ 01-31"
     */
    public String monthRangeLabel() {
        return monthStart.format(LABEL_FMT) + " ~ " + monthEnd.format(LABEL_FMT);
    }

    /**
     * 분기 레이블. 예: "2026 Q1 (01-01 ~ 03-31)"
     */
    public String quarterLabel() {
        return String.format("%d Q%d (%s ~ %s)",
                year, quarter,
                quarterStart.format(LABEL_FMT),
                quarterEnd.format(LABEL_FMT));
    }

    /**
     * 페이지 제목 접두 정보. 예: "2026 Q1 1월"
     * (전체 페이지 제목은 MonthlyReportService.buildMonthlyTitle() 에서 팀명 포함하여 생성)
     */
    public String pageMetaLabel() {
        return String.format("%d Q%d %d월", year, quarter, month);
    }

    /**
     * 분기 디렉토리 제목. 예: "2026년 월간 Q1"
     */
    public String quarterDirTitle() {
        return String.format("%d년 월간 Q%d", year, quarter);
    }

    /**
     * 연도 디렉토리 제목. 예: "2026년 월간"
     */
    public String yearDirTitle() {
        return String.format("%d년 월간", year);
    }

    // =========================================================================
    // MonthlyTicketItem — 티켓 단위 DTO
    // WeeklyTicketItem 과 동일한 구조, Monthly 컨텍스트 전용 타입으로 분리
    // =========================================================================

    /**
     * 월간보고 티켓 항목
     */
    @Data
    @Builder(toBuilder = true)
    public static class MonthlyTicketItem {

        /**
         * Jira 이슈 키 (예: CCE-123)
         */
        private String issueKey;

        /**
         * 이슈 제목
         */
        private String summary;

        /**
         * 담당자 이름
         */
        private String assigneeName;

        /**
         * Jira 상태 코드
         */
        private JiraStatusCode status;

        /**
         * Jira 실제 상태명 (예: "완료", "Done")
         */
        private String statusName;

        /**
         * 시작일 (Jira customfield_10015 / extendedProperties["jiraStartDate"]).
         * 기능 추가 이전에 생성된 이벤트는 이 값이 없을 수 있음 → null 허용.
         */
        private LocalDate startDate;

        /**
         * 완료일 (완료 티켓) 또는 기한일 (진행중 티켓).
         * 캘린더 이벤트 start.date 기반 (= Jira duedate). null 허용.
         */
        private LocalDate dueDate;

        /**
         * Jira 우선순위.
         * description의 "Priority: " 라인으로부터 파싱.
         * 없거나 파싱 불가 시 {@link JiraPriorityCode#UNKNOWN}.
         */
        private JiraPriorityCode priority;

        /**
         * Jira 이슈 URL
         */
        private String url;

        /**
         * 카테고리 (주문/회원/수당/포인트/ABO/RBO)
         */
        private String category;

        /**
         * 이슈 여부 ([이슈] 태그 포함)
         */
        private boolean issue;

        /**
         * 원본 프로젝트 키 (디버깅용)
         */
        private String projectKey;
    }
}
