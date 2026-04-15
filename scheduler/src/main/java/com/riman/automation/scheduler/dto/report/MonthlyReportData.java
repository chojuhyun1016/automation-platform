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
 * 월간보고 데이터 컨테이너이다. 대상 월의 1일부터 말일까지를 보고 기간으로 하며,
 * 카테고리별 완료/진행중/이슈 티켓을 보관한다. 카테고리 분류 규칙은 WeeklyReportData에 위임하여 일관성을 유지한다.
 * Confluence 페이지 계층은 실적보고 → 2026년 월간 → 2026년 월간 Q1 → 2026 Q1 1월 - 보상코어 개발팀 실적 순이다.
 */
@Data
@Builder
public class MonthlyReportData {

  /** 보고서 기준일이다. Lambda 실행일이다. */
  private LocalDate baseDate;

  /** 대상 월 시작일(1일)이다. 예: 2026-01-01. */
  private LocalDate monthStart;

  /** 대상 월 종료일(말일)이다. 예: 2026-01-31. */
  private LocalDate monthEnd;

  /** 연도이다(예: 2026). */
  private int year;

  /** 대상 월이다(1~12). */
  private int month;

  /** 분기이다(1~4). */
  private int quarter;

  /** 분기 시작일이다(예: 2026-01-01). */
  private LocalDate quarterStart;

  /** 분기 종료일이다(예: 2026-03-31). */
  private LocalDate quarterEnd;

  /** 카테고리별 대상 월 완료 티켓 맵이다. key는 카테고리명이다. */
  private Map<String, List<MonthlyTicketItem>> doneByCategory;

  /** 카테고리별 분기 전체 진행중 티켓 맵이다. */
  private Map<String, List<MonthlyTicketItem>> inProgressByCategory;

  /** 카테고리별 이슈 티켓 맵이다. [이슈] 태그가 포함된 미완료 티켓이 해당된다. */
  private Map<String, List<MonthlyTicketItem>> issuesByCategory;

  /** 카테고리 표시 순서이다. WeeklyReportData와 동일한 순서를 유지한다. */
  public static final List<String> CATEGORY_ORDER =
      List.of("주문", "회원", "수당", "포인트", "ABO", "RBO");

  /**
   * 프로젝트 키와 제목으로 카테고리를 반환한다. WeeklyReportData.detectCategory와 동일한 규칙을 적용한다.
   *
   * @param projectKey 티켓 프로젝트 키(예: "CCE", "RBO")
   * @param summary    티켓 제목
   * @return 카테고리명, 분류 불가면 null
   */
  public static String detectCategory(String projectKey, String summary) {
    return WeeklyReportData.detectCategory(projectKey, summary);
  }

  /**
   * 제목에 "[이슈]" 태그가 포함되면 이슈로 판별한다.
   */
  public static boolean detectIssue(String summary) {
    return WeeklyReportData.detectIssue(summary);
  }

  private static final DateTimeFormatter LABEL_FMT =
      DateTimeFormatter.ofPattern("MM-dd");

  /**
   * 기간 문자열을 반환한다(예: "01-01 ~ 01-31").
   */
  public String monthRangeLabel() {
    return monthStart.format(LABEL_FMT) + " ~ " + monthEnd.format(LABEL_FMT);
  }

  /**
   * 분기 레이블을 반환한다(예: "2026 Q1 (01-01 ~ 03-31)").
   */
  public String quarterLabel() {
    return String.format("%d Q%d (%s ~ %s)",
        year, quarter,
        quarterStart.format(LABEL_FMT),
        quarterEnd.format(LABEL_FMT));
  }

  /**
   * 페이지 제목 접두 정보를 반환한다(예: "2026 Q1 1월").
   * 전체 페이지 제목은 MonthlyReportService.buildMonthlyTitle()에서 팀명을 포함하여 생성한다.
   */
  public String pageMetaLabel() {
    return String.format("%d Q%d %d월", year, quarter, month);
  }

  /**
   * 분기 디렉토리 제목을 반환한다(예: "2026년 월간 Q1").
   */
  public String quarterDirTitle() {
    return String.format("%d년 월간 Q%d", year, quarter);
  }

  /**
   * 연도 디렉토리 제목을 반환한다(예: "2026년 월간").
   */
  public String yearDirTitle() {
    return String.format("%d년 월간", year);
  }

  /**
   * 월간보고 티켓 항목이다. WeeklyTicketItem과 동일 구조이나 Monthly 컨텍스트 전용 타입으로 분리한다.
   */
  @Data
  @Builder(toBuilder = true)
  public static class MonthlyTicketItem {

    /** Jira 이슈 키이다(예: CCE-123). */
    private String issueKey;

    /** 이슈 제목이다. */
    private String summary;

    /** 담당자 이름이다. */
    private String assigneeName;

    /** Jira 상태 코드이다. */
    private JiraStatusCode status;

    /** Jira 실제 상태명이다(예: "완료", "Done"). */
    private String statusName;

    /**
     * 시작일이다. Jira customfield_10015 또는 extendedProperties["jiraStartDate"]에서 파싱한다.
     * 기능 추가 이전 이벤트는 값이 없을 수 있으며 null을 허용한다.
     */
    private LocalDate startDate;

    /**
     * 완료 티켓은 완료일, 진행 중 티켓은 기한일을 나타낸다.
     * 캘린더 이벤트 start.date 기반이며 Jira duedate와 대응한다. null을 허용한다.
     */
    private LocalDate dueDate;

    /**
     * Jira 우선순위이다. description의 "Priority: " 라인에서 파싱하며 없거나 파싱 불가 시 UNKNOWN이다.
     */
    private JiraPriorityCode priority;

    /** Jira 이슈 URL이다. */
    private String url;

    /** 카테고리(주문/회원/수당/포인트/ABO/RBO)이다. */
    private String category;

    /** 이슈 여부이다. [이슈] 태그가 포함되면 true이다. */
    private boolean issue;

    /** 원본 프로젝트 키이다(디버깅용). */
    private String projectKey;
  }
}
