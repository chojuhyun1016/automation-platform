package com.riman.automation.scheduler.dto.report;

import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.code.JiraPriorityCode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 주간보고 데이터 컨테이너
 *
 * <p><b>카테고리 분류 기준:</b>
 * <pre>
 *  카테고리  | 포함 조건
 *  ──────────────────────────────────────────────────────────────────
 *  주문      | CCE 프로젝트 + 제목에 [주문] 포함
 *  회원      | CCE 프로젝트 + 제목에 [회원] 포함
 *            | GER(Global Exception Request) 프로젝트 전체
 *            | KEEN 프로젝트 전체
 *  수당      | CCE 프로젝트 + 제목에 [수당] 포함
 *  포인트    | CCE 프로젝트 + 제목에 [포인트] 포함
 *  ABO       | ABO 프로젝트(ABO_Operation) 전체
 *            | CCE 프로젝트 + 제목에 [ABO] 포함
 *  RBO       | RBO 프로젝트(RBO Myoffice 마이오피스) 전체
 *            | CCE 프로젝트 + 제목에 [RBO] 포함
 * </pre>
 *
 * <p><b>이슈 판별:</b>
 * 제목에 {@code "[이슈]"} 포함 + 미완료 상태.
 * 예: {@code "[회원][이슈] 로그인 오류"} → 회원 카테고리의 이슈
 *
 * <p><b>페이지 계층:</b>
 * 부모 페이지 → 분기 디렉토리(2026년 주간 Q2) → 주간보고 페이지
 */
@Data
@Builder
public class WeeklyReportData {

    // =========================================================================
    // 메타
    // =========================================================================

    /**
     * 보고서 기준일 (Lambda 실행일, 월요일)
     */
    private LocalDate baseDate;

    /**
     * 전주 시작일 (지난 월요일)
     */
    private LocalDate weekStart;

    /**
     * 전주 종료일 (지난 일요일)
     */
    private LocalDate weekEnd;

    /**
     * ISO 8601 주차 번호 (예: 14)
     */
    private int weekNumber;

    /**
     * 연도 (예: 2026)
     */
    private int year;

    /**
     * 분기 (1~4)
     */
    private int quarter;

    /**
     * 분기 시작일 (예: 2026-04-01)
     */
    private LocalDate quarterStart;

    /**
     * 분기 종료일 (예: 2026-06-30)
     */
    private LocalDate quarterEnd;

    // =========================================================================
    // 티켓 데이터 — 카테고리별 분류
    // key: 카테고리명 (주문/회원/수당/포인트/ABO/RBO)
    // =========================================================================

    /**
     * 카테고리 → 이번 주 완료 티켓
     */
    private Map<String, List<WeeklyTicketItem>> doneByCategory;

    /**
     * 카테고리 → 분기 전체 진행중 티켓
     */
    private Map<String, List<WeeklyTicketItem>> inProgressByCategory;

    /**
     * 카테고리 → 이슈 티켓 ([이슈] 태그 + 미완료)
     */
    private Map<String, List<WeeklyTicketItem>> issuesByCategory;

    // =========================================================================
    // 카테고리 상수
    // =========================================================================

    /**
     * 카테고리 표시 순서 (Confluence 섹션 순서와 일치)
     */
    public static final List<String> CATEGORY_ORDER =
            List.of("주문", "회원", "수당", "포인트", "ABO", "RBO");

    // =========================================================================
    // 카테고리 분류 (static)
    // =========================================================================

    /**
     * 프로젝트 키 + 제목으로 카테고리를 반환한다.
     *
     * <p><b>프로젝트별 분류 규칙:</b>
     * <ul>
     *   <li>RBO  → "RBO"</li>
     *   <li>ABO  → "ABO"</li>
     *   <li>GER  → "회원" (Global Exception Request)</li>
     *   <li>KEEN → "회원"</li>
     *   <li>CCE  → 제목 태그([주문]/[회원]/[수당]/[포인트]/[ABO]/[RBO]) 기반 분류</li>
     *   <li>그 외 → null (보고서 미포함)</li>
     * </ul>
     *
     * <p>새 프로젝트가 추가되면 이 메서드의 switch 문에만 추가하면 된다.
     * WeeklyReportConfig / MonthlyReportConfig 의 jira_project_keys 와 무관하게
     * 이 메서드가 카테고리 분류의 단일 진실 공급원(single source of truth)이다.
     *
     * @param projectKey 티켓 프로젝트 키 (예: "CCE", "RBO")
     * @param summary    티켓 제목
     * @return 카테고리명, 분류 불가면 null (보고서 미포함)
     */
    public static String detectCategory(String projectKey, String summary) {
        if (projectKey == null) return null;
        return switch (projectKey.toUpperCase()) {
            case "RBO" -> "RBO";
            case "ABO" -> "ABO";
            case "GADMIN" -> "ABO";
            case "GER" -> "회원";
            case "KEEN" -> "회원";
            case "CCE" -> detectCceCategory(summary);
            default -> null;
        };
    }

    /**
     * CCE 티켓 카테고리 — 제목 태그 기반 분류.
     *
     * <p>태그 검사 순서: 주문 → 회원 → 수당 → 포인트 → ABO → RBO
     * 첫 번째 매칭 태그가 카테고리가 된다.
     *
     * <p>예시:
     * <ul>
     *   <li>{@code "[회원][이슈] 로그인 오류"} → "회원"</li>
     *   <li>{@code "[ABO] 정산 처리"} → "ABO"</li>
     *   <li>{@code "태그없는 티켓"} → null (미분류, 보고서 미포함)</li>
     * </ul>
     */
    private static String detectCceCategory(String summary) {
        if (summary == null) return null;
        for (String cat : CATEGORY_ORDER) {
            if (summary.contains("[" + cat + "]")) return cat;
        }
        return null;
    }

    /**
     * 이슈 여부 — 제목에 {@code "[이슈]"} 포함.
     */
    public static boolean detectIssue(String summary) {
        return summary != null && summary.contains("[이슈]");
    }

    // =========================================================================
    // 표시용 유틸
    // =========================================================================

    private static final DateTimeFormatter LABEL_FMT =
            DateTimeFormatter.ofPattern("MM-dd");

    /**
     * 기간 문자열. 예: "03-30 ~ 04-05"
     */
    public String weekRangeLabel() {
        return weekStart.format(LABEL_FMT) + " ~ " + weekEnd.format(LABEL_FMT);
    }

    /**
     * 주간보고 페이지 제목 접두 정보. 예: "2026 W10"
     * (전체 페이지 제목은 WeeklyReportService.buildWeeklyTitle() 에서 팀명 포함하여 생성)
     */
    public String pageTitle() {
        return String.format("%d W%02d", year, weekNumber);
    }

    /**
     * 분기 디렉토리 제목. 예: "2026년 주간 Q2"
     */
    public String quarterDirTitle() {
        return String.format("%d년 주간 Q%d", year, quarter);
    }

    /**
     * 연도 디렉토리 제목. 예: "2026년 주간"
     */
    public String yearDirTitle() {
        return String.format("%d년 주간", year);
    }

    /**
     * 분기 레이블. 예: "2026 Q2 (04-01 ~ 06-30)"
     */
    public String quarterLabel() {
        return String.format("%d Q%d (%s ~ %s)",
                year, quarter,
                quarterStart.format(LABEL_FMT),
                quarterEnd.format(LABEL_FMT));
    }

    // =========================================================================
    // WeeklyTicketItem — 티켓 단위 DTO
    // =========================================================================

    /**
     * 주간보고 티켓 항목
     */
    @Data
    @Builder(toBuilder = true)
    public static class WeeklyTicketItem {

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
