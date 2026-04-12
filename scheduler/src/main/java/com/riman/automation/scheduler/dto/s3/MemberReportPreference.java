package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 팀원별 보고서 커스터마이징 설정
 *
 * <p>scheduler-config.json 의 {@code dailyReport.member_overrides} 맵 값.
 *
 * <pre>
 * "member_overrides": {
 *   "조주현": {
 *     "sections": ["announcements", "absences", "tickets", "overdue_tickets",
 *                   "team_tickets", "today_schedules", "links"],
 *     "jira_project_keys": ["CCE", "RBO"]
 *   }
 * }
 * </pre>
 *
 * <p><b>sections 값:</b>
 * <ul>
 *   <li>{@code announcements} — 팀 공지</li>
 *   <li>{@code absences} — 부재/재택 현황</li>
 *   <li>{@code tickets} — 티켓 현황</li>
 *   <li>{@code overdue_tickets} — 미완료 티켓 현황</li>
 *   <li>{@code team_tickets} — 팀원 총괄 티켓 (Manager 전용)</li>
 *   <li>{@code today_schedules} — 오늘 일정</li>
 *   <li>{@code links} — 주요 페이지 링크</li>
 * </ul>
 *
 * <p>sections 가 null 이면 dailyReport.default_sections 를 사용한다.
 * jira_project_keys 가 null 이면 dailyReport.jira_project_keys 를 사용한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberReportPreference {

    /**
     * 보고서에 포함할 섹션 목록.
     * null 이면 dailyReport.default_sections 사용.
     */
    private List<String> sections;

    /**
     * 이 팀원에게 표시할 Jira 프로젝트 키 목록.
     * null 이면 dailyReport.jira_project_keys 사용.
     */
    @JsonProperty("jira_project_keys")
    private List<String> jiraProjectKeys;

    /**
     * 지정된 섹션 목록에 해당 섹션이 포함되어 있는지 확인한다.
     * sections 가 null 이면 항상 true (default_sections 에 위임).
     */
    public boolean hasSection(String sectionName) {
        if (sections == null) return true;
        return sections.contains(sectionName);
    }
}
