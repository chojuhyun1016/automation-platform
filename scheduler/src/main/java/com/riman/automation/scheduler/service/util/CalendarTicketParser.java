package com.riman.automation.scheduler.service.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 캘린더 이벤트 공통 파싱/재검증 유틸
 *
 * <p>WeeklyCalendarTicketCollector와 MonthlyCalendarTicketCollector의
 * 100% 중복 코드를 단일 위치로 통합한 stateless 유틸 클래스.
 * 두 Collector는 이 클래스에 위임하여 중복 없이 동일한 파싱 로직을 공유한다.
 *
 * <p><b>캘린더 이벤트 제목 규칙:</b>
 * <pre>
 *   형식 1: "[Jira] CCE-2326 (조주현)"
 *   형식 2: "[CCE-123] 로그인 버튼 오류 (홍길동, 김철수)"
 * </pre>
 */
@Slf4j
public final class CalendarTicketParser {

    /**
     * 이슈 키 패턴
     * <ul>
     *   <li>형식 1: {@code "[Jira] CCE-2326 (조주현)"}</li>
     *   <li>형식 2: {@code "[CCE-123] 제목 (이름)"}</li>
     * </ul>
     */
    public static final Pattern ISSUE_KEY_PATTERN =
            Pattern.compile("\\[Jira\\]\\s+([A-Z]+-\\d+)|\\[([A-Z]+-\\d+)\\]");

    /**
     * 담당자 이름 패턴: 제목 끝의 (홍길동) 또는 (홍길동, 김철수)
     */
    public static final Pattern ASSIGNEE_PATTERN =
            Pattern.compile("\\(([^)]+)\\)\\s*$");

    private CalendarTicketParser() {
    }

    // =========================================================================
    // 담당자 파싱
    // =========================================================================

    public static List<String> parseAssigneeNames(String title) {
        if (title == null) return List.of();
        Matcher m = ASSIGNEE_PATTERN.matcher(title);
        if (!m.find()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            String n = part.trim();
            if (!n.isEmpty()) names.add(n);
        }
        return names;
    }

    /**
     * 팀원 목록과 매칭되는 첫 번째 이름 반환.
     * 매칭 없으면 이벤트 담당자 첫 번째 이름 반환.
     */
    public static String resolveAssigneeName(List<String> assigneeNames, List<TeamMember> members) {
        if (assigneeNames.isEmpty()) return "미배정";
        for (String name : assigneeNames) {
            for (TeamMember m : members) {
                if (name.trim().equals(m.effectiveCalendarName())) return m.getName();
            }
        }
        return assigneeNames.get(0);
    }

    // =========================================================================
    // 이슈 키 추출
    // =========================================================================

    /**
     * 이벤트 제목에서 이슈 키 추출. 없으면 null 반환.
     */
    public static String extractIssueKey(String title) {
        Matcher m = ISSUE_KEY_PATTERN.matcher(title);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /**
     * 이슈 키에서 프로젝트 키 추출. 예: "CCE-123" → "CCE"
     */
    public static String extractProjectKey(String issueKey) {
        if (issueKey == null || !issueKey.contains("-")) return "";
        return issueKey.substring(0, issueKey.lastIndexOf('-'));
    }

    // =========================================================================
    // Summary 추출
    // =========================================================================

    /**
     * 이벤트 요약(제목) 추출.
     * description의 "Title: " 라인 우선, 없으면 캘린더 제목에서 이슈 키/담당자/이모지 제거.
     */
    public static String extractSummary(Event event, String title) {
        if (event.getDescription() != null) {
            for (String line : event.getDescription().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Title: ")) {
                    String t = trimmed.substring("Title: ".length()).trim();
                    if (!t.isBlank()) return t;
                }
            }
        }
        String s = ISSUE_KEY_PATTERN.matcher(title).replaceAll("").trim();
        s = ASSIGNEE_PATTERN.matcher(s).replaceAll("").trim();
        s = s.replaceAll("[🔴🟠🟡🟢⚪⚫]", "").trim();
        return s.isBlank() ? title : s;
    }

    // =========================================================================
    // 상태/우선순위/날짜 감지
    // =========================================================================

    /**
     * description의 "Status: " 라인에서 상태 감지.
     * 없으면 IN_PROGRESS 반환 (Jira 재검증 대상).
     */
    public static JiraStatusCode detectStatus(String description) {
        if (description != null) {
            for (String line : description.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Status: ")) {
                    return JiraStatusCode.fromStatusName(
                            trimmed.substring("Status: ".length()).trim());
                }
            }
        }
        return JiraStatusCode.IN_PROGRESS;
    }

    /**
     * description의 "Priority: " 라인에서 우선순위 감지.
     * 없으면 UNKNOWN 반환.
     */
    public static JiraPriorityCode detectPriority(String description) {
        if (description != null) {
            for (String line : description.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Priority: ")) {
                    return JiraPriorityCode.from(
                            trimmed.substring("Priority: ".length()).trim());
                }
            }
        }
        return JiraPriorityCode.UNKNOWN;
    }

    /**
     * 시작일 감지 — 2단계 fallback.
     * 1) extendedProperties["jiraStartDate"]
     * 2) description "Start Date: " 라인
     * 3) null (FIX-4 이전 이벤트)
     */
    public static LocalDate detectStartDate(Event event) {
        try {
            if (event.getExtendedProperties() != null
                    && event.getExtendedProperties().getPrivate() != null) {
                String val = event.getExtendedProperties().getPrivate().get("jiraStartDate");
                if (val != null && !val.isBlank()) return DateTimeUtil.parseDate(val);
            }
        } catch (Exception e) {
            log.debug("[CalendarTicketParser] extendedProperties jiraStartDate 파싱 실패: {}", e.getMessage());
        }
        if (event.getDescription() != null) {
            for (String line : event.getDescription().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Start Date: ")) {
                    String val = trimmed.substring("Start Date: ".length()).trim();
                    try {
                        if (!val.isBlank()) return DateTimeUtil.parseDate(val);
                    } catch (Exception e) {
                        log.debug("[CalendarTicketParser] Start Date 파싱 실패: '{}'", val);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 이벤트 날짜 추출 (캘린더 start.date = Jira duedate).
     */
    public static LocalDate dateOf(Event event) {
        if (event.getStart() == null) return null;
        com.google.api.client.util.DateTime date = event.getStart().getDate();
        if (date != null) {
            return DateTimeUtil.parseDate(date.toStringRfc3339().substring(0, 10));
        }
        com.google.api.client.util.DateTime dateTime = event.getStart().getDateTime();
        if (dateTime != null) {
            String str = dateTime.toStringRfc3339();
            return DateTimeUtil.parseDate(str.length() >= 10 ? str.substring(0, 10) : str);
        }
        return null;
    }

    public static String titleOf(Event event) {
        return event.getSummary() != null ? event.getSummary() : "";
    }

    // =========================================================================
    // Jira 상태 재검증 공통
    // =========================================================================

    /**
     * IN_PROGRESS 상태 티켓을 Jira API로 배치 조회하여 실제 상태 맵 반환.
     * jiraClient가 null이거나 조회 실패 시 빈 맵 반환 (degraded mode).
     */
    public static Map<String, JiraStatusCode> fetchStatusFromJira(
            Set<String> issueKeys, JiraClient jiraClient, String logTag) {
        Map<String, JiraStatusCode> map = new HashMap<>();
        if (jiraClient == null || issueKeys.isEmpty()) return map;
        try {
            String jql = "issueKey in (" + String.join(", ", issueKeys) + ")";
            JsonNode res = jiraClient.search(jql, "status", 0);
            JsonNode issues = res.path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    String key = issue.path("key").asText();
                    String catKey = issue.path("fields").path("status")
                            .path("statusCategory").path("key").asText();
                    map.put(key, JiraStatusCode.fromCategoryKey(catKey));
                }
            }
            log.info("[{}] Jira 배치 조회 완료: {}건", logTag, map.size());
        } catch (Exception e) {
            log.warn("[{}] Jira 상태 재검증 실패, 건너뜀: {}", logTag, e.getMessage());
        }
        return map;
    }
}
