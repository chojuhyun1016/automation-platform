package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 팀원별 보고서 커스터마이징 설정이다. scheduler-config.json의 dailyReport.member_overrides 맵 값과 매핑된다.
 * sections 유효 값은 announcements, absences, tickets, overdue_tickets, team_tickets, today_schedules, links이다.
 * sections가 null이면 dailyReport.default_sections를, jira_project_keys가 null이면 dailyReport.jira_project_keys를 사용한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberReportPreference {

  /** 보고서에 포함할 섹션 목록이다. null이면 dailyReport.default_sections를 사용한다. */
  private List<String> sections;

  /** 이 팀원에게 표시할 Jira 프로젝트 키 목록이다. null이면 dailyReport.jira_project_keys를 사용한다. */
  @JsonProperty("jira_project_keys")
  private List<String> jiraProjectKeys;

  /**
   * 지정된 섹션 목록에 해당 섹션이 포함되어 있는지 확인한다.
   * sections가 null이면 항상 true를 반환하며 default_sections에 판정을 위임한다.
   */
  public boolean hasSection(String sectionName) {
    if (sections == null) return true;
    return sections.contains(sectionName);
  }
}
