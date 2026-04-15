package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 일일 보고서 설정이다. S3 config.json의 "dailyReport" 섹션과 매핑된다.
 * default_sections와 member_overrides로 팀원별 섹션/프로젝트 필터링을 제공하며,
 * 향후 주간/월간 보고서는 weeklyReport, monthlyReport 섹션으로 별도 추가된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyReportConfig {

  /** 보고서 활성화 여부이다. */
  private Boolean enabled = true;

  /**
   * 보고서를 전송할 Slack 채널 ID이다.
   * "channel" 또는 "dm:{slackUserId}" 형식을 지원하며 향후 "dm:all" 등 대량 전송 확장을 염두에 둔다.
   */
  @JsonProperty("report_channel_id")
  private String reportChannelId;

  /** 재택/부재 조회용 Google Calendar ID이다. */
  @JsonProperty("calendar_id")
  private String calendarId;

  /**
   * 티켓 이벤트 조회용 Google Calendar ID이다.
   * 이벤트 제목 형식은 "[CCE-123] 제목 (담당자이름)"이며 calendar_id와 동일 또는 별도 캘린더 모두 허용된다.
   */
  @JsonProperty("ticket_calendar_id")
  private String ticketCalendarId;

  /**
   * /일정등록 커맨드 이벤트 조회용 Google Calendar ID이다.
   * worker ScheduleFacade가 일정을 등록하는 캘린더와 동일해야 하며 미설정 시 오늘 일정 섹션을 출력하지 않는다.
   */
  @JsonProperty("schedule_calendar_id")
  private String scheduleCalendarId;

  /** 보고서에 포함할 Jira 프로젝트 키 목록이다. */
  @JsonProperty("jira_project_keys")
  private List<String> jiraProjectKeys;

  /**
   * 팀 공지 파일의 S3 오브젝트 키이다. 버킷은 configBucket과 동일하며 키만 별도 지정한다.
   * 미설정(null) 시 공지 없음으로 처리한다.
   */
  @JsonProperty("announcements_key")
  private String announcementsKey;

  /** 주요 페이지 링크 목록이다. */
  private List<PageLink> links;

  /**
   * 보고서 기본 섹션 목록이다. member_overrides에 해당 팀원 설정이 없을 때 사용한다.
   * null이면 모든 섹션을 포함한다. 유효 값은 announcements, absences, tickets, overdue_tickets,
   * team_tickets, today_schedules, links이다.
   */
  @JsonProperty("default_sections")
  private List<String> defaultSections;

  /**
   * 팀원별 보고서 커스터마이징 설정 맵이다.
   * key는 팀원 한글 이름(team-members.json의 name 필드와 일치)이며 null이면 전원이 default_sections를 사용한다.
   */
  @JsonProperty("member_overrides")
  private Map<String, MemberReportPreference> memberOverrides;

  /**
   * 해당 팀원의 섹션 포함 여부를 확인한다.
   * 우선순위는 member_overrides[name].sections → default_sections → 전체 포함 순이다.
   *
   * @param memberName  팀원 한글 이름
   * @param sectionName 섹션 이름
   * @return 섹션 포함 여부
   */
  public boolean isSectionEnabled(String memberName, String sectionName) {
    if (memberOverrides != null && memberName != null) {
      MemberReportPreference pref = memberOverrides.get(memberName);
      if (pref != null && pref.getSections() != null) {
        return pref.getSections().contains(sectionName);
      }
    }
    if (defaultSections != null) {
      return defaultSections.contains(sectionName);
    }
    return true;
  }

  /**
   * 해당 팀원의 Jira 프로젝트 키 목록을 반환한다.
   * 우선순위는 member_overrides[name].jira_project_keys → dailyReport.jira_project_keys 순이다.
   *
   * @param memberName 팀원 한글 이름
   * @return Jira 프로젝트 키 목록
   */
  public List<String> getEffectiveJiraProjectKeys(String memberName) {
    if (memberOverrides != null && memberName != null) {
      MemberReportPreference pref = memberOverrides.get(memberName);
      if (pref != null && pref.getJiraProjectKeys() != null
          && !pref.getJiraProjectKeys().isEmpty()) {
        return pref.getJiraProjectKeys();
      }
    }
    return jiraProjectKeys;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PageLink {
    private String title;
    private String url;
  }
}
