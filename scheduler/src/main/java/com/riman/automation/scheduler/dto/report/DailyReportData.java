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
 * 일일 보고서 데이터 컨테이너이다. 수집 계층이 채우고 포맷 계층이 읽는 DTO로 동작하며,
 * period 필드로 일간/주간/월간을 구분해 포맷터가 분기한다. 현재는 DAILY만 사용한다.
 */
@Data
@Builder
public class DailyReportData {

  /**
   * 보고서 대상 팀원 이름이다. 섹션 필터링에 사용된다.
   */
  private String memberName;

  /** 보고서 기준일(KST 오늘)이다. */
  private LocalDate baseDate;

  /** 보고서 주기 코드이다. */
  private ReportPeriodCode period;

  /** 날짜 필터링된 활성 공지 목록이다. */
  private List<AnnouncementItem> announcements;

  /** 부재/재택 이벤트 목록이다. */
  private List<AbsenceItem> absences;

  /** 우선순위와 due date로 정렬된 활성 티켓 목록이다. */
  private List<TicketItem> tickets;

  /**
   * 팀원별 티켓 맵으로 Manager 보고서 전용이다.
   * key는 팀원 이름, value는 해당 팀원의 활성 티켓 목록이다. null이면 Engineer 보고서이며 팀원 총괄 섹션을 출력하지 않는다.
   */
  private LinkedHashMap<String, List<TicketItem>> teamTickets;

  /** 주요 페이지 링크 목록이다. */
  private List<PageLinkItem> links;

  /**
   * 오늘 일정 목록이다. /일정등록 커맨드로 등록된 본인 당일 일정이며 null 또는 빈 리스트이면 섹션을 출력하지 않는다.
   * 정렬은 종일 일정 우선, 이후 startTime 오름차순이다.
   */
  private List<ScheduleItem> todaySchedules;

  @Data
  @Builder
  public static class AbsenceItem {
    /** 팀원 한글 이름이며 캘린더 이벤트에서 파싱한다. */
    private String memberName;
    /** 근무 상태 코드이다. */
    private WorkStatusCode workStatus;
    /** 해당 날짜이다. */
    private LocalDate date;
    /** 오늘 여부이며 빨강 강조 기준으로 사용된다. */
    private boolean today;
  }

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
    /** 색깔 차등 표시 기준 코드이다. */
    private DueDateUrgencyCode urgency;
    /** Jira 이슈 URL(클릭 링크용)이다. */
    private String url;
  }

  @Data
  @Builder
  public static class PageLinkItem {
    private String title;
    private String url;
  }

  /**
   * /일정등록 커맨드로 등록된 Google Calendar 이벤트 1건이다.
   * DailyScheduleCollector 반환 시 종일 일정 우선, 시간 지정은 startTime 오름차순으로 정렬된다.
   * 표시 형식은 시간 지정인 경우 [HH:mm-HH:mm] 제목, 종일인 경우 [종일] 제목이며,
   * url이 존재하면 Slack mrkdwn의 &lt;url|제목&gt; 형식으로 클릭 가능하게 렌더링된다.
   */
  @Data
  @Builder
  public static class ScheduleItem {

    /** Google Calendar 이벤트 제목이다(예: "[일정] 주간 회의"). */
    private String title;

    /**
     * 종일 일정 여부이다. true이면 startTime/endTime을 무시하고 "[종일]"로 표시한다.
     */
    private boolean allDay;

    /** 시작 시각(KST)이다. allDay=true이면 null이다. */
    private LocalTime startTime;

    /** 종료 시각(KST)이다. allDay=true이면 null이다. */
    private LocalTime endTime;

    /**
     * 연결 URL이며 캘린더 이벤트 description에서 파싱한다.
     * null 또는 빈 문자열이면 링크가 없다.
     */
    private String url;
  }
}
