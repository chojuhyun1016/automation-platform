package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 프로젝트 그룹 설정이다. 보고서를 그룹별로 분리할 때 사용하며
 * scheduler-config.json의 weeklyReport.project_groups 또는 monthlyReport.project_groups 배열 항목과 매핑된다.
 * project_groups가 설정되면 그룹별로 별도 Confluence 페이지를 생성하고, 미설정 시 전체 카테고리 단일 페이지로 동작한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectGroup {

  /** 그룹 이름이다. Confluence 페이지 제목에 사용된다(예: "주문/수당"). */
  private String name;

  /**
   * 이 그룹에 포함할 카테고리 목록이다.
   * WeeklyReportData.CATEGORY_ORDER의 값과 일치해야 한다(예: ["주문", "수당", "포인트"]).
   */
  private List<String> categories;

  /**
   * categories의 방어적 반환이다. null이면 빈 리스트를 반환한다.
   */
  public List<String> getEffectiveCategories() {
    return categories != null ? categories : List.of();
  }
}
