package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 월간(실적) 보고 설정 DTO
 *
 * <p>S3 {@code scheduler-config.json}의 {@code "monthlyReport"} 섹션과 매핑된다.
 *
 * <pre>
 * {
 *   "monthlyReport": {
 *     "enabled": true,
 *     "team_name": "보상코어 개발팀",
 *     "ticket_calendar_id": "abcd@group.calendar.google.com",
 *     "confluence_base_url": "https://riman-it.atlassian.net",
 *     "confluence_space_key": "IT",
 *     "confluence_parent_page_id": "2337538054"
 *   }
 * }
 * </pre>
 *
 * <p><b>카테고리 분류는 설정이 아닌 코드로 관리:</b>
 * 프로젝트 키 → 카테고리 매핑은 {@link com.riman.automation.scheduler.dto.report.WeeklyReportData#detectCategory}
 * 의 switch 문에 하드코딩되어 있다. 새 프로젝트 추가 시 해당 메서드만 수정하면 된다.
 * MonthlyReportData 는 이 메서드에 완전 위임한다.
 *
 * <p><b>Confluence 페이지 계층 구조:</b>
 * <pre>
 *   [confluence_parent_page_id]  "실적보고"            ← 이미 존재, 직접 생성 안 함
 *     └─ "2026년 월간"                                 ← 연도 페이지,  없으면 자동 생성
 *         └─ "2026년 월간 Q1"                          ← 분기 페이지,  없으면 자동 생성
 *             └─ "2026 Q1 1월 - 보상코어 개발팀 실적"   ← 월간보고 페이지
 * </pre>
 *
 * <p><b>보고 기간:</b>
 * 대상 월의 1일부터 말일까지 (예: 1월 → 01-01 ~ 01-31).
 * Lambda 실행 시 {@code target_month} 파라미터가 없으면 이전 월을 자동 사용.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonthlyReportConfig {

    /**
     * 월간보고 활성화 여부
     */
    private Boolean enabled = true;

    /**
     * 팀명 — Confluence 월간보고 페이지 제목 접미사에 사용.
     * <br>예: "보상코어 개발팀" → 페이지 제목: "2026 Q1 1월 - 보상코어 개발팀 실적"
     * <br>미설정 시 기본값 "보상코어 개발팀" 사용.
     */
    @JsonProperty("team_name")
    private String teamName = "보상코어 개발팀";

    /**
     * 티켓 이벤트 조회용 Google Calendar ID.
     *
     * <p>캘린더 이벤트 제목 규칙: "[CCE-123] 제목 (담당자이름)"
     *
     * <p><b>필수 설정:</b> 미설정 시 월간보고 데이터 수집 불가.
     */
    @JsonProperty("ticket_calendar_id")
    private String ticketCalendarId;

    /**
     * Confluence 베이스 URL.
     * <br>예: https://riman-it.atlassian.net  (/wiki 미포함 — ConfluenceClient 가 자동 추가)
     */
    @JsonProperty("confluence_base_url")
    private String confluenceBaseUrl;

    /**
     * Confluence Space Key.
     * <br>예: "IT"
     */
    @JsonProperty("confluence_space_key")
    private String confluenceSpaceKey;

    /**
     * 월간보고 루트 부모 페이지 ID.
     *
     * <p>"실적보고"처럼 이미 존재하는 최상위 페이지 ID.
     * 이 페이지 하위에 연도 → 분기 → 월간보고 계층이 자동 생성된다.
     *
     * <p>Confluence URL에서 확인:
     * <br>{@code https://riman-it.atlassian.net/wiki/spaces/IT/pages/2337538054}
     * <br>→ pageId = "2337538054"
     */
    @JsonProperty("confluence_parent_page_id")
    private String confluenceParentPageId;
}
