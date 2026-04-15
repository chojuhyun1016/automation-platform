package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 팀원 정보 DTO이다. team-members.json의 members[] 배열 한 항목과 1:1 매핑된다.
 * calendar_name 필드가 없으므로 name 필드를 그대로 캘린더 이벤트 담당자 매칭에 사용한다.
 * 예: 이벤트 제목 "[CCE-123] 제목 (조주현)"은 name="조주현"과 매칭된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamMember {

  /** 한글 이름이다. 보고서 표시와 캘린더 이벤트 담당자 매칭에 사용된다. */
  private String name;

  /** 영문 이름이다(미사용, 파일 호환용). */
  @JsonProperty("name_en")
  private String nameEn;

  /** 이메일이다(미사용, 파일 호환용). */
  private String email;

  /** Jira Account ID이다. Jira 담당자 필터링에 사용된다. */
  @JsonProperty("jira_account_id")
  private String jiraAccountId;

  /** Slack User ID(U로 시작)이다. 개인 DM 전송 대상 식별자이다. */
  @JsonProperty("slack_user_id")
  private String slackUserId;

  /** 활성 여부이다. false이면 보고서 대상에서 제외된다. */
  private Boolean active = true;

  /** 팀 코드이다(예: CCE). */
  private String team;

  /** 역할이다(Manager 또는 Engineer). */
  private String role;

  /**
   * 캘린더 이벤트에서 담당자를 매칭할 이름을 반환한다.
   * 별도 calendar_name 필드가 없으므로 name을 그대로 사용한다.
   * 캘린더 이벤트 제목 규칙은 "[CCE-123] 제목 (이름)"이다.
   */
  public String effectiveCalendarName() {
    return name;
  }

  public boolean isActive() {
    return !Boolean.FALSE.equals(active);
  }

  /**
   * 관리자(Manager) 여부를 반환한다. role이 "Manager"(대소문자 무관)이면 true이다.
   */
  public boolean isManager() {
    return "Manager".equalsIgnoreCase(role);
  }

  /**
   * 일반 팀원 여부를 반환한다. Manager가 아닌 모든 역할이 true이다.
   */
  public boolean isEngineer() {
    return !isManager();
  }
}
