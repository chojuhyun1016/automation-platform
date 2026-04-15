package com.riman.automation.worker.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 팀 멤버 DTO.
 * S3 team-members.json의 members[] 배열 원소와 매핑되며, 역직렬화 전용이다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamMember {

  /** 한글 이름. */
  private String name;

  /** 영문 이름. */
  @JsonProperty("name_en")
  private String nameEn;

  /** 이메일 주소. */
  private String email;

  /** Jira Account ID. */
  @JsonProperty("jira_account_id")
  private String jiraAccountId;

  /** Slack User ID. */
  @JsonProperty("slack_user_id")
  private String slackUserId;

  /** 활성 여부. */
  private Boolean active;

  /** 팀명. */
  private String team;

  /** 역할. */
  private String role;
}
