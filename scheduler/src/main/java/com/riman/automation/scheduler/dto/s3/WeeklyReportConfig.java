package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 주간 실적 보고 설정 DTO이다. S3 scheduler-config.json의 "weeklyReport" 섹션과 매핑된다.
 * 카테고리 분류는 설정이 아닌 WeeklyReportData.detectCategory 코드 매핑으로 관리되므로 새 프로젝트 추가 시 해당 메서드만 수정한다.
 * Confluence 페이지 계층은 confluence_parent_page_id 하위에 연도 → 분기 → 주간보고 페이지가 자동 생성된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeeklyReportConfig {

  /** 주간보고 활성화 여부이다. */
  private Boolean enabled = true;

  /**
   * 팀명이다. Confluence 주간보고 페이지 제목 접미사로 사용한다.
   * 예: "보상코어 개발팀" → "2026 1월 W4 - 보상코어 개발팀 실적". 미설정 시 기본값 "보상코어 개발팀"을 사용한다.
   */
  @JsonProperty("team_name")
  private String teamName = "보상코어 개발팀";

  /**
   * 티켓 이벤트 조회용 Google Calendar ID이다.
   * 이벤트 제목 규칙은 "[CCE-123] 제목 (담당자이름)"이며 미설정 시 주간보고 데이터를 수집할 수 없다.
   */
  @JsonProperty("ticket_calendar_id")
  private String ticketCalendarId;

  /**
   * Confluence 베이스 URL이다.
   * 예: https://riman-it.atlassian.net. /wiki는 포함하지 않으며 ConfluenceClient가 자동 추가한다.
   */
  @JsonProperty("confluence_base_url")
  private String confluenceBaseUrl;

  /** Confluence Space Key이다(예: "IT"). */
  @JsonProperty("confluence_space_key")
  private String confluenceSpaceKey;

  /**
   * 주간보고 루트 부모 페이지 ID이다.
   * "실적보고"처럼 이미 존재하는 최상위 페이지 ID이며 이 페이지 하위에 연도/분기/주간보고 계층이 자동 생성된다.
   * Confluence URL의 /pages/{id} 경로에서 확인할 수 있다.
   */
  @JsonProperty("confluence_parent_page_id")
  private String confluenceParentPageId;

  /**
   * 보고서 생성 실패 시 Slack 알림을 받을 사용자 ID이다. 미설정 시 실패 알림은 로그에만 남긴다.
   */
  @JsonProperty("error_notify_slack_user_id")
  private String errorNotifySlackUserId;

  /**
   * 프로젝트 그룹별 보고서 분리 설정이다.
   * 설정 시 그룹별로 별도 Confluence 페이지를 생성하며 미설정 시 전체 카테고리 단일 페이지로 생성한다.
   */
  @JsonProperty("project_groups")
  private List<ProjectGroup> projectGroups;

  /**
   * 프로젝트 그룹 분리 활성화 여부를 반환한다.
   */
  public boolean isGroupSeparationEnabled() {
    return projectGroups != null && !projectGroups.isEmpty();
  }
}
