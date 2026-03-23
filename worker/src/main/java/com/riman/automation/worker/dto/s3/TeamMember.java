package com.riman.automation.worker.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 팀 멤버 DTO
 *
 * <p>S3 {@code team-members.json}의 {@code members[]} 배열 원소와 매핑되는 DTO.
 * Jackson 역직렬화 전용이며 비즈니스 로직을 포함하지 않는다.
 *
 * <pre>
 * {
 *   "name":            "조주현",
 *   "name_en":         "Ju Hyun Cho",
 *   "email":           "juhyun.cho@riman.com",
 *   "jira_account_id": "712020:...",
 *   "slack_user_id":   "U0627755JP7",
 *   "active":          true,
 *   "team":            "개발팀",
 *   "role":            "백엔드"
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamMember {

    /**
     * 한글 이름 (예: 조주현)
     */
    private String name;

    /**
     * 영문 이름 (예: Ju Hyun Cho)
     */
    @JsonProperty("name_en")
    private String nameEn;

    /**
     * 이메일
     */
    private String email;

    /**
     * Jira Account ID
     */
    @JsonProperty("jira_account_id")
    private String jiraAccountId;

    /**
     * Slack User ID (예: U0627755JP7)
     */
    @JsonProperty("slack_user_id")
    private String slackUserId;

    /**
     * 활성 여부
     */
    private Boolean active;

    /**
     * 팀명
     */
    private String team;

    /**
     * 역할
     */
    private String role;
}
