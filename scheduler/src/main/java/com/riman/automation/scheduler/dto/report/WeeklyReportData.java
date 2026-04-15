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
 * 주간보고 데이터 컨테이너이다. 카테고리별로 분류된 완료/진행중/이슈 티켓을 보관한다.
 * 카테고리 분류는 프로젝트 키와 제목 태그 기반으로 이루어지며, "[이슈]" 태그가 포함되고 미완료 상태이면 이슈로 판별한다.
 * Confluence 페이지 계층은 부모 페이지 → 분기 디렉토리(예: 2026년 주간 Q2) → 주간보고 페이지 순으로 구성된다.
 */
@Data
@Builder
public class WeeklyReportData {

  /** 보고서 기준일이다. Lambda 실행일이며 보통 월요일이다. */
  private LocalDate baseDate;

  /** 전주 시작일(지난 월요일)이다. */
  private LocalDate weekStart;

  /** 전주 종료일(지난 일요일)이다. */
  private LocalDate weekEnd;

  /** ISO 8601 주차 번호이다(예: 14). */
  private int weekNumber;

  /** 연도이다(예: 2026). */
  private int year;

  /** 분기이다(1~4). */
  private int quarter;

  /** 분기 시작일이다(예: 2026-04-01). */
  private LocalDate quarterStart;

  /** 분기 종료일이다(예: 2026-06-30). */
  private LocalDate quarterEnd;

  /** 카테고리별 이번 주 완료 티켓 맵이다. key는 카테고리명이다. */
  private Map<String, List<WeeklyTicketItem>> doneByCategory;

  /** 카테고리별 분기 전체 진행중 티켓 맵이다. */
  private Map<String, List<WeeklyTicketItem>> inProgressByCategory;

  /** 카테고리별 이슈 티켓 맵이다. [이슈] 태그가 포함된 미완료 티켓이 해당된다. */
  private Map<String, List<WeeklyTicketItem>> issuesByCategory;

  /** 카테고리 표시 순서이다. Confluence 섹션 순서와 일치한다. */
  public static final List<String> CATEGORY_ORDER =
      List.of("주문", "회원", "수당", "포인트", "ABO", "RBO");

  /**
   * 프로젝트 키와 제목으로 카테고리를 반환한다.
   * RBO/ABO/GADMIN은 해당 카테고리로, GER/KEEN은 "회원"으로 매핑한다.
   * CCE는 제목 태그 기반으로 세부 분류하며 매칭되지 않으면 null을 반환해 보고서에 포함하지 않는다.
   * 이 메서드가 카테고리 분류의 단일 진실 공급원으로 설정 파일의 project 키와 무관하게 동작한다.
   *
   * @param projectKey 티켓 프로젝트 키(예: "CCE", "RBO")
   * @param summary    티켓 제목
   * @return 카테고리명, 분류 불가면 null
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
   * CCE 티켓의 카테고리를 제목 태그 기반으로 분류한다.
   * 검사 순서는 CATEGORY_ORDER와 동일하며 첫 번째 매칭 태그가 카테고리가 된다.
   * 예: "[회원][이슈] 로그인 오류" → "회원", "태그없는 티켓" → null.
   */
  private static String detectCceCategory(String summary) {
    if (summary == null) return null;
    for (String cat : CATEGORY_ORDER) {
      if (summary.contains("[" + cat + "]")) return cat;
    }
    return null;
  }

  /**
   * 제목에 "[이슈]" 태그가 포함되면 이슈로 판별한다.
   */
  public static boolean detectIssue(String summary) {
    return summary != null && summary.contains("[이슈]");
  }

  private static final DateTimeFormatter LABEL_FMT =
      DateTimeFormatter.ofPattern("MM-dd");

  /**
   * 기간 문자열을 반환한다(예: "03-30 ~ 04-05").
   */
  public String weekRangeLabel() {
    return weekStart.format(LABEL_FMT) + " ~ " + weekEnd.format(LABEL_FMT);
  }

  /**
   * 주간보고 페이지 제목 접두 정보를 반환한다(예: "2026 W10").
   * 전체 페이지 제목은 WeeklyReportService.buildWeeklyTitle()에서 팀명을 포함하여 생성한다.
   */
  public String pageTitle() {
    return String.format("%d W%02d", year, weekNumber);
  }

  /**
   * 분기 디렉토리 제목을 반환한다(예: "2026년 주간 Q2").
   */
  public String quarterDirTitle() {
    return String.format("%d년 주간 Q%d", year, quarter);
  }

  /**
   * 연도 디렉토리 제목을 반환한다(예: "2026년 주간").
   */
  public String yearDirTitle() {
    return String.format("%d년 주간", year);
  }

  /**
   * 분기 레이블을 반환한다(예: "2026 Q2 (04-01 ~ 06-30)").
   */
  public String quarterLabel() {
    return String.format("%d Q%d (%s ~ %s)",
        year, quarter,
        quarterStart.format(LABEL_FMT),
        quarterEnd.format(LABEL_FMT));
  }

  /**
   * 주간보고 티켓 항목이다. Confluence 페이지 렌더링에 사용되는 단위 DTO이다.
   */
  @Data
  @Builder(toBuilder = true)
  public static class WeeklyTicketItem {

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
