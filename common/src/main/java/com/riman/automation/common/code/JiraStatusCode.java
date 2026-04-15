package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * Jira 이슈 상태 코드.
 * 보고서 관점에서 미완료(IN_PROGRESS)와 완료(DONE) 2값 체계로 분류한다.
 *
 * 감지 방식:
 * - fromCategoryKey: Jira REST API의 statusCategory.key 기준. JiraCollector에서 사용.
 * - fromStatusName: Jira 실제 상태명 기준. CalendarTicketCollector에서 캘린더 description 파싱 시 사용.
 *
 * 프로젝트별 완료 상태명 (DONE_STATUS_NAMES):
 *   RBO: Done, Answered, Duplicated, Grit 이관, Listed, Reject
 *   ABO: Done, DUPLICATED, Monitoring In Progress
 *   CCE: 완료, 반려, 취소
 */
@Getter
@RequiredArgsConstructor
public enum JiraStatusCode {

  /** 미완료. To Do, In Progress 등 아직 처리 중인 모든 상태. 보고서 포함 대상. */
  IN_PROGRESS("indeterminate", "진행중", "🔵"),

  /** 완료. DONE_STATUS_NAMES 또는 statusCategory.key=done에 해당. 보고서 제외 대상. */
  DONE("done", "완료", "✅");

  /**
   * Jira 실제 상태명 기준 완료 판단 목록.
   * 대소문자를 구분하지 않고 비교한다.
   * 새 프로젝트가 추가되거나 완료 상태명이 변경되면 이 목록만 수정한다.
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

  /** Jira REST API statusCategory.key */
  private final String categoryKey;
  private final String displayName;
  private final String emoji;

  /**
   * Jira REST API의 statusCategory.key 기준 변환.
   * "done"이면 DONE, 그 외("new", "indeterminate" 등)는 모두 IN_PROGRESS.
   *
   * @param categoryKey statusCategory.key 값 (null 허용)
   */
  public static JiraStatusCode fromCategoryKey(String categoryKey) {
    if (categoryKey == null) return IN_PROGRESS;
    return "done".equalsIgnoreCase(categoryKey) ? DONE : IN_PROGRESS;
  }

  /**
   * Jira 실제 상태명 기준 변환.
   * DONE_STATUS_NAMES에 포함된 이름(대소문자 무관)이면 DONE, 그 외는 IN_PROGRESS.
   *
   * @param statusName Jira 상태명 (예: "Done", "완료", "In Progress") - null 허용
   */
  public static JiraStatusCode fromStatusName(String statusName) {
    if (statusName == null || statusName.isBlank()) return IN_PROGRESS;
    return DONE_STATUS_NAMES.contains(statusName.trim().toLowerCase())
        ? DONE : IN_PROGRESS;
  }

  /**
   * 보고서 포함 대상 여부. DONE이 아닌 모든 상태가 보고서에 포함된다.
   */
  public boolean isActive() {
    return this != DONE;
  }
}
