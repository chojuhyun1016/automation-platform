package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 팀원 정보 DTO — team-members.json 의 members[] 배열 한 항목과 1:1 매핑
 *
 * <p><b>실제 team-members.json 구조:</b>
 * <pre>
 * {
 *   "version": "1.1",
 *   "members": [
 *     {
 *       "name"           : "조주현",
 *       "slack_user_id"  : "U0627755JP7",
 *       "jira_account_id": "712020:408dbc01-...",
 *       "active"         : true,
 *       "team"           : "CCE",
 *       "role"           : "Manager"
 *     }, ...
 *   ]
 * }
 * </pre>
 *
 * <p><b>calendar_name 필드 없음:</b>
 * 기존 파일에 calendar_name이 없으므로 name 필드를 그대로 캘린더 매칭에 사용.
 * 캘린더 이벤트 제목의 담당자 이름이 name과 정확히 일치해야 함.
 * (예: 이벤트 제목 "[CCE-123] 제목 (조주현)" → name="조주현" 으로 매칭)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamMember {

    /**
     * 한글 이름 — 보고서 표시, 캘린더 이벤트 담당자 매칭에 사용
     */
    private String name;

    /**
     * 영문 이름 (미사용, 파일 호환용)
     */
    @JsonProperty("name_en")
    private String nameEn;

    /**
     * 이메일 (미사용, 파일 호환용)
     */
    private String email;

    /**
     * Jira Account ID — Jira 담당자 필터링용
     */
    @JsonProperty("jira_account_id")
    private String jiraAccountId;

    /**
     * Slack User ID (U로 시작) — 개인 DM 전송 대상
     */
    @JsonProperty("slack_user_id")
    private String slackUserId;

    /**
     * 활성 여부 — false 이면 보고서 대상 제외
     */
    private Boolean active = true;

    /**
     * 팀 코드 (예: CCE)
     */
    private String team;

    /**
     * 역할 (Manager / Engineer)
     */
    private String role;

    /**
     * 캘린더 이벤트에서 담당자를 매칭할 이름
     *
     * <p>별도 calendar_name 필드 없으므로 name을 그대로 사용.
     * 캘린더 이벤트 제목 규칙: "[CCE-123] 제목 (이름)"
     */
    public String effectiveCalendarName() {
        return name;
    }

    public boolean isActive() {
        return !Boolean.FALSE.equals(active);
    }

    /**
     * 관리자(Manager) 여부. role="Manager"(대소문자 무관)이면 true.
     */
    public boolean isManager() {
        return "Manager".equalsIgnoreCase(role);
    }

    /**
     * 일반 팀원(Manager가 아닌 모든 역할) 여부.
     */
    public boolean isEngineer() {
        return !isManager();
    }
}
