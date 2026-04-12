package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 프로젝트 그룹 설정 — 보고서를 그룹별로 분리할 때 사용
 *
 * <p>scheduler-config.json 의 weeklyReport.project_groups / monthlyReport.project_groups 배열 항목.
 *
 * <pre>
 * "project_groups": [
 *   { "name": "주문/수당", "categories": ["주문", "수당", "포인트"] },
 *   { "name": "회원/ABO/RBO", "categories": ["회원", "ABO", "RBO"] }
 * ]
 * </pre>
 *
 * <p>project_groups 가 설정되면 그룹별로 별도 Confluence 페이지를 생성한다.
 * 미설정(null/빈 배열) 시 기존 동작 유지 (전체 카테고리 하나의 페이지).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectGroup {

    /**
     * 그룹 이름 — Confluence 페이지 제목에 사용.
     * 예: "주문/수당"
     */
    private String name;

    /**
     * 이 그룹에 포함할 카테고리 목록.
     * WeeklyReportData.CATEGORY_ORDER 의 값과 일치해야 한다.
     * 예: ["주문", "수당", "포인트"]
     */
    private List<String> categories;

    /**
     * categories 의 방어적 반환 — null 이면 빈 리스트.
     */
    public List<String> getEffectiveCategories() {
        return categories != null ? categories : List.of();
    }
}
