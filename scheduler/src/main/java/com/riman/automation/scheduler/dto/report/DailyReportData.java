package com.riman.automation.scheduler.dto.report;

import com.riman.automation.common.code.*;
import com.riman.automation.scheduler.dto.s3.AnnouncementItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.LinkedHashMap;

/**
 * 보고서 데이터 컨테이너
 *
 * <p>수집 계층(DailyJiraTicketCollector, DailyAbsenceCollector)이 채우고,
 * 포맷 계층(ReportFormatter, DailyReportService)이 읽는 DTO.
 *
 * <p>period 필드로 일간/주간/월간을 구분해 포맷터가 분기한다.
 * 현재는 DAILY만 사용.
 */
@Data
@Builder
public class DailyReportData {

    /**
     * 보고서 기준일 (KST 오늘)
     */
    private LocalDate baseDate;

    /**
     * 보고서 주기
     */
    private ReportPeriodCode period;

    /**
     * 팀 공지 — 날짜 필터 완료된 활성 항목 목록
     */
    private List<AnnouncementItem> announcements;

    /**
     * 부재/재택 이벤트
     */
    private List<AbsenceItem> absences;

    /**
     * 활성 티켓 목록 (우선순위 + due date 정렬 완료)
     */
    private List<TicketItem> tickets;

    /**
     * 팀원별 티켓 맵 — Manager 보고서 전용.
     * key: 팀원 이름, value: 해당 팀원의 활성 티켓 목록.
     * null 이면 팀원 총괄 섹션 미출력(Engineer 보고서).
     */
    private LinkedHashMap<String, List<TicketItem>> teamTickets;

    /**
     * 주요 페이지 링크
     */
    private List<PageLinkItem> links;

    /**
     * 오늘 일정 목록 — /일정등록 커맨드로 등록된 본인 당일 일정.
     * null 또는 빈 리스트이면 오늘 일정 섹션 미출력.
     * 정렬: 종일 일정 우선, 이후 startTime 오름차순.
     */
    private List<ScheduleItem> todaySchedules;

    // =========================================================================
    // AbsenceItem
    // =========================================================================

    @Data
    @Builder
    public static class AbsenceItem {
        /**
         * 팀원 한글 이름 (캘린더 이벤트에서 파싱)
         */
        private String memberName;
        /**
         * 근무 상태
         */
        private WorkStatusCode workStatus;
        /**
         * 해당 날짜
         */
        private LocalDate date;
        /**
         * 오늘 여부 — 빨강 강조 기준
         */
        private boolean today;
    }

    // =========================================================================
    // TicketItem
    // =========================================================================

    @Data
    @Builder(toBuilder = true)
    public static class TicketItem {
        private String issueKey;
        private String summary;
        private String projectKey;
        private String assigneeName;
        private String assigneeAccountId;
        private JiraStatusCode status;
        private JiraPriorityCode priority;
        private LocalDate dueDate;
        /**
         * DueDateUrgencyCode — 색깔 차등 표시 기준
         */
        private DueDateUrgencyCode urgency;
        /**
         * Jira 이슈 URL (클릭 링크용)
         */
        private String url;
    }

    // =========================================================================
    // PageLinkItem
    // =========================================================================

    @Data
    @Builder
    public static class PageLinkItem {
        private String title;
        private String url;
    }

    // =========================================================================
    // ScheduleItem — /일정등록 커맨드로 등록된 당일 일정
    // =========================================================================

    /**
     * 오늘 일정 항목 — /일정등록 커맨드로 등록된 Google Calendar 이벤트 1건.
     *
     * <p><b>정렬 기준 (DailyScheduleCollector 반환 시 적용):</b>
     * <ol>
     *   <li>종일 일정({@code allDay=true}) 우선</li>
     *   <li>시간 지정 일정은 {@code startTime} 오름차순</li>
     * </ol>
     *
     * <p><b>표시 형식:</b>
     * <ul>
     *   <li>시간 지정: {@code [HH:mm-HH:mm] 제목}
     *       (예: {@code [09:00-10:00] 주간 회의})</li>
     *   <li>종일: {@code [종일] 제목}</li>
     * </ul>
     *
     * <p>{@code url}이 존재하면 Slack mrkdwn {@code <url|제목>} 형식으로 클릭 가능하게 표시.
     */
    @Data
    @Builder
    public static class ScheduleItem {

        /**
         * Google Calendar 이벤트 제목 (예: "[일정] 주간 회의")
         */
        private String title;

        /**
         * 종일 일정 여부
         * true이면 startTime/endTime을 무시하고 "[종일]"로 표시
         */
        private boolean allDay;

        /**
         * 시작 시각 (KST). allDay=true이면 null.
         */
        private LocalTime startTime;

        /**
         * 종료 시각 (KST). allDay=true이면 null.
         */
        private LocalTime endTime;

        /**
         * 연결 URL (캘린더 이벤트 description에서 파싱).
         * null 또는 빈 문자열이면 링크 없음.
         */
        private String url;
    }
}
