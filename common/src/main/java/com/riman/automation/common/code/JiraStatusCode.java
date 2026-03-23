package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * Jira 이슈 상태 코드
 *
 * <p><b>보고서 관점의 2값 체계:</b> 미완료(IN_PROGRESS) / 완료(DONE)
 * <ul>
 *   <li>{@link #IN_PROGRESS} — 미완료. To Do, In Progress 모두 포함.
 *       보고서에 포함되어야 하는 모든 활성 티켓.</li>
 *   <li>{@link #DONE} — 완료. 프로젝트마다 다른 완료 상태명을 {@link #DONE_STATUS_NAMES}로 관리.</li>
 * </ul>
 *
 * <p><b>감지 방식 2가지:</b>
 * <ol>
 *   <li>{@link #fromCategoryKey(String)} — Jira REST API의 statusCategory.key 기준.
 *       JiraCollector처럼 Jira API를 직접 호출할 때 사용.</li>
 *   <li>{@link #fromStatusName(String)} — Jira 실제 상태명(statusName) 기준.
 *       CalendarTicketCollector처럼 캘린더 description의 "Status: ..." 라인을 파싱할 때 사용.</li>
 * </ol>
 *
 * <p><b>프로젝트별 완료 상태명 ({@link #DONE_STATUS_NAMES}):</b>
 * <pre>
 *   RBO: Done, Answered, Duplicated, Grit 이관, Listed, Reject
 *   ABO: Done, DUPLICATED, Monitoring In Progress
 *   CCE: 완료, 반려, 취소
 * </pre>
 * 위 목록에 해당하면 DONE, 그 외는 모두 IN_PROGRESS로 처리한다.
 */
@Getter
@RequiredArgsConstructor
public enum JiraStatusCode {

    /**
     * 미완료 — To Do, In Progress 등 아직 처리 중인 모든 상태.
     * 보고서 포함 대상.
     */
    IN_PROGRESS("indeterminate", "진행중", "🔵"),

    /**
     * 완료 — 프로젝트별 완료 상태명({@link #DONE_STATUS_NAMES}) 또는
     * statusCategory.key = "done" 에 해당하는 모든 상태.
     * 보고서 제외 대상.
     */
    DONE("done", "완료", "✅");

    // =========================================================================
    // 프로젝트별 완료 상태명 목록
    // =========================================================================

    /**
     * Jira 실제 상태명 기준 완료 판단 목록.
     *
     * <p>대소문자를 구분하지 않고 비교한다 ({@link #fromStatusName(String)} 참고).
     *
     * <pre>
     * RBO  : Done, Answered, Duplicated, Grit 이관, Listed, Reject
     * ABO  : Done, DUPLICATED, Monitoring In Progress
     * CCE  : 완료, 반려, 취소
     * </pre>
     *
     * <p>새 프로젝트가 추가되거나 완료 상태명이 변경되면 이 목록만 수정한다.
     */
    private static final Set<String> DONE_STATUS_NAMES = Set.of(
            // RBO
            "done",
            "answered",
            "duplicated",
            "grit 이관",
            "listed",
            "reject",
            // ABO
            "monitoring in progress",
            // CCE
            "완료",
            "반려",
            "취소"
    );

    // =========================================================================
    // 필드
    // =========================================================================

    /**
     * Jira REST API statusCategory.key
     */
    private final String categoryKey;
    private final String displayName;
    private final String emoji;

    // =========================================================================
    // 팩토리 메서드
    // =========================================================================

    /**
     * Jira REST API의 {@code statusCategory.key} 기준 변환.
     *
     * <p>JiraCollector처럼 Jira API 응답을 직접 파싱할 때 사용.
     * <ul>
     *   <li>{@code "done"} → {@link #DONE}</li>
     *   <li>{@code "new"}, {@code "indeterminate"}, 그 외 모두 → {@link #IN_PROGRESS}</li>
     * </ul>
     *
     * @param categoryKey statusCategory.key 값 (null 허용)
     */
    public static JiraStatusCode fromCategoryKey(String categoryKey) {
        if (categoryKey == null) return IN_PROGRESS;
        return "done".equalsIgnoreCase(categoryKey) ? DONE : IN_PROGRESS;
    }

    /**
     * Jira 실제 상태명({@code statusName}) 기준 변환.
     *
     * <p>CalendarTicketCollector처럼 캘린더 이벤트 description의
     * {@code "Status: ..."} 라인을 파싱할 때 사용.
     *
     * <p>{@link #DONE_STATUS_NAMES}에 포함된 이름(대소문자 무관)이면 {@link #DONE},
     * 그 외는 {@link #IN_PROGRESS}.
     *
     * @param statusName Jira 상태명 (예: "Done", "완료", "In Progress") — null 허용
     */
    public static JiraStatusCode fromStatusName(String statusName) {
        if (statusName == null || statusName.isBlank()) return IN_PROGRESS;
        return DONE_STATUS_NAMES.contains(statusName.trim().toLowerCase())
                ? DONE : IN_PROGRESS;
    }

    // =========================================================================
    // 상태 판별
    // =========================================================================

    /**
     * 보고서 포함 대상 여부.
     *
     * <p>DONE이 아닌 모든 상태(= IN_PROGRESS)가 보고서에 포함된다.
     */
    public boolean isActive() {
        return this != DONE;
    }
}
