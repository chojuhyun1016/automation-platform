package com.riman.automation.scheduler.service.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.DueDateUrgencyCode;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.code.ReportWeekCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.report.DailyReportData.TicketItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Jira 데이터 수집 서비스
 *
 * <p>JiraClient 호출 → JSON 파싱 → TicketItem 목록 반환.
 * 포맷, 비즈니스 판단 없음.
 *
 * <p>포함 기준:
 * <ul>
 *   <li>statusCategory: new(To Do) + indeterminate(In Progress) — 미완료 상태만 수집</li>
 *   <li>금주 범위의 due date (금요일이면 차주 금요일까지)</li>
 *   <li>정렬: 우선순위 높은 것 → due date 임박 순</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DailyJiraTicketCollector {

    private static final String DEFAULT_FIELDS =
            "summary,status,priority,assignee,duedate,project,issuetype";

    private final JiraClient jiraClient;
    private final String jiraBaseUrl;

    /**
     * 활성 티켓 수집
     *
     * @param projectKeys Jira 프로젝트 키 목록
     * @param baseDate    기준일 (KST 오늘)
     */
    public List<TicketItem> collect(List<String> projectKeys, LocalDate baseDate) {
        if (projectKeys == null || projectKeys.isEmpty()) return List.of();

        LocalDate endDate = ReportWeekCode.endDate(baseDate);
        String jql = buildJql(projectKeys, endDate);

        JsonNode result = jiraClient.search(jql, DEFAULT_FIELDS, 0); // 0 = 전체 수집 (제한 없음)
        List<TicketItem> items = parseIssues(result, baseDate);

        // 우선순위 오름차순(1=Highest) → due date 임박 순(null은 마지막)
        items.sort(Comparator
                .comparingInt((TicketItem t) -> t.getPriority().getOrder())
                .thenComparingInt(t -> t.getDueDate() == null ? Integer.MAX_VALUE
                        : (int) java.time.temporal.ChronoUnit.DAYS.between(baseDate, t.getDueDate()))
        );

        log.info("[DailyJiraTicketCollector] 수집 완료: {}건", items.size());
        return items;
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private String buildJql(List<String> projects, LocalDate endDate) {
        return "project in (" + String.join(", ", projects) + ")"
                + " AND statusCategory in ('new', 'indeterminate')"
                + " AND issuetype != Epic"
                + " AND (duedate <= '" + DateTimeUtil.formatDate(endDate) + "'"
                + "      OR duedate is EMPTY)"
                + " ORDER BY priority ASC, duedate ASC";
    }

    private List<TicketItem> parseIssues(JsonNode result, LocalDate baseDate) {
        List<TicketItem> list = new ArrayList<>();
        JsonNode issues = result.path("issues");
        if (!issues.isArray()) return list;

        for (JsonNode issue : issues) {
            try {
                list.add(parseIssue(issue, baseDate));
            } catch (Exception e) {
                log.warn("[DailyJiraTicketCollector] 이슈 파싱 실패: key={}", issue.path("key").asText(), e);
            }
        }
        return list;
    }

    private TicketItem parseIssue(JsonNode issue, LocalDate baseDate) {
        String key = issue.path("key").asText();
        JsonNode f = issue.path("fields");

        String summary = f.path("summary").asText("");
        String projectKey = f.path("project").path("key").asText("");
        String assigneeName = f.path("assignee").path("displayName").asText("미배정");
        String assigneeId = f.path("assignee").path("accountId").asText("");

        String catKey = f.path("status").path("statusCategory").path("key").asText();
        JiraStatusCode status = JiraStatusCode.fromCategoryKey(catKey);
        JiraPriorityCode priority = JiraPriorityCode.from(f.path("priority").path("name").asText());

        LocalDate dueDate = DateTimeUtil.parseDate(f.path("duedate").asText(null));
        DueDateUrgencyCode urgency = DueDateUrgencyCode.of(baseDate, dueDate);

        String url = (jiraBaseUrl != null ? jiraBaseUrl : "https://riman-it.atlassian.net")
                + "/browse/" + key;

        return TicketItem.builder()
                .issueKey(key).summary(summary).projectKey(projectKey)
                .assigneeName(assigneeName).assigneeAccountId(assigneeId)
                .status(status).priority(priority)
                .dueDate(dueDate).urgency(urgency).url(url)
                .build();
    }
}
