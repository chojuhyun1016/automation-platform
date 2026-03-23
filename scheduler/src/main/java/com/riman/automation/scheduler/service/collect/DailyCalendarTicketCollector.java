package com.riman.automation.scheduler.service.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.DueDateUrgencyCode;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.code.ReportWeekCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.report.DailyReportData.TicketItem;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 일일 보고용 Google Calendar 기반 담당 티켓 수집기
 *
 * <p><b>DailyAbsenceCollector 와의 대응:</b>
 * <pre>
 *   DailyAbsenceCollector  — 부재/재택 이벤트 수집
 *   DailyCalendarTicketCollector   — 담당 티켓 이벤트 수집  (이 클래스)
 * </pre>
 *
 * <p><b>WeeklyCalendarTicketCollector 와의 차이:</b>
 * <pre>
 *   DailyCalendarTicketCollector       — Google Calendar 이벤트 기반, 오늘 날짜 기준 담당 티켓
 *   WeeklyCalendarTicketCollector — Jira JQL 직접 조회, 전주 완료/분기 진행중 티켓
 * </pre>
 *
 * <p><b>캘린더 이벤트 제목 규칙:</b>
 * <pre>
 *   형식 1: "[Jira] CCE-2326 (조주현)"
 *   형식 2: "[CCE-123] 로그인 버튼 오류 (홍길동, 김철수)"
 * </pre>
 *
 * <p><b>수집 기준:</b>
 * <ul>
 *   <li>기간: 이번 주 월~금, 금요일이면 차주 금요일까지</li>
 *   <li>due date: 이벤트 날짜를 due date로 사용</li>
 *   <li>우선순위: 이벤트 제목의 🔴🟠🟡🟢⚪ 이모지로 감지 (없으면 MEDIUM)</li>
 *   <li>상태: description 의 Status 라인 우선, 없으면 IN_PROGRESS</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DailyCalendarTicketCollector {

    /**
     * 이슈 키 패턴 — 두 가지 형식 지원:
     * <ul>
     *   <li>형식 1: {@code "[Jira] CCE-2326 (조주현)"}</li>
     *   <li>형식 2: {@code "[CCE-123] 제목 (이름)"}</li>
     * </ul>
     */
    private static final Pattern ISSUE_KEY_PATTERN =
            Pattern.compile("\\[Jira\\]\\s+([A-Z]+-\\d+)|\\[([A-Z]+-\\d+)\\]");

    /**
     * 담당자 이름 패턴: 제목 끝의 (홍길동) 또는 (홍길동, 김철수)
     */
    private static final Pattern ASSIGNEE_PATTERN =
            Pattern.compile("\\(([^)]+)\\)\\s*$");

    private final GoogleCalendarClient calendarClient;
    private final String jiraBaseUrl;

    /**
     * Jira API 클라이언트 (nullable).
     *
     * <p>null 이면 Jira 상태 재검증 건너뜀.
     * Google Calendar description 의 Status 라인만으로 상태를 판단한다.
     */
    private final JiraClient jiraClient;

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * 특정 팀원의 티켓 수집.
     *
     * <p>캘린더 전체 이벤트에서 이 팀원이 담당자인 것만 필터링한다.
     *
     * @param calendarId 티켓 캘린더 ID (scheduler-config.json ticket_calendar_id)
     * @param member     조회 대상 팀원
     * @param baseDate   기준일 (KST 오늘)
     * @return 이 팀원이 담당하는 TicketItem 목록 (우선순위+기한 정렬 완료)
     */
    public List<TicketItem> collectForMember(String calendarId,
                                             TeamMember member,
                                             LocalDate baseDate) {
        if (calendarId == null || calendarId.isBlank()) {
            log.warn("[DailyCalendarTicketCollector] ticket_calendar_id 미설정, 티켓 수집 건너뜀");
            return List.of();
        }

        LocalDate startDate = ReportWeekCode.startDate(baseDate);
        LocalDate endDate = ReportWeekCode.endDate(baseDate);

        String timeMin = startDate + "T00:00:00+09:00";
        String timeMax = endDate + "T23:59:59+09:00";
        String memberName = member.effectiveCalendarName();

        log.info("[DailyCalendarTicketCollector] 수집: member={}, {} ~ {}", memberName, startDate, endDate);

        List<Event> events = calendarClient.listEvents(calendarId, timeMin, timeMax, memberName);

        List<TicketItem> tickets = new ArrayList<>();
        for (Event event : events) {
            try {
                TicketItem item = parseTicketEvent(event, member, baseDate);
                if (item != null) tickets.add(item);
            } catch (Exception e) {
                log.warn("[DailyCalendarTicketCollector] 이벤트 파싱 실패: title={}", event.getSummary(), e);
            }
        }

        tickets.sort(Comparator
                .comparingInt((TicketItem t) -> t.getDueDate() == null
                        ? Integer.MAX_VALUE
                        : (int) java.time.temporal.ChronoUnit.DAYS.between(baseDate, t.getDueDate()))
                .thenComparingInt(t -> t.getPriority().getOrder()));

        log.info("[DailyCalendarTicketCollector] 수집 완료: member={}, {}건", memberName, tickets.size());
        return tickets;
    }

    /**
     * 팀원 전체의 티켓을 한꺼번에 수집.
     *
     * <p>금주/차주 범위 + 과거 미완료 범위(6개월)를 각각 조회하여 병합.
     * 캘린더를 2회만 조회하고 팀원별로 분류하여 API 호출 횟수를 최소화한다.
     *
     * @param calendarId 티켓 캘린더 ID
     * @param members    팀원 목록
     * @param baseDate   기준일
     * @return 팀원 → TicketItem 목록 (팀원 순서 보장)
     */
    public Map<TeamMember, List<TicketItem>> collectAllMembers(
            String calendarId, List<TeamMember> members, LocalDate baseDate) {

        if (calendarId == null || calendarId.isBlank()) {
            log.warn("[DailyCalendarTicketCollector] ticket_calendar_id 미설정");
            return Map.of();
        }

        LocalDate startDate = ReportWeekCode.startDate(baseDate);
        LocalDate endDate = ReportWeekCode.endDate(baseDate);

        // ── 1) 금주/차주 이벤트 조회 ─────────────────────────────────────
        String timeMin = startDate + "T00:00:00+09:00";
        String timeMax = endDate + "T23:59:59+09:00";

        log.info("[DailyCalendarTicketCollector] 전체 이벤트 수집: {} ~ {}", startDate, endDate);
        List<Event> allEvents = calendarClient.listEvents(calendarId, timeMin, timeMax, null);
        log.info("[DailyCalendarTicketCollector] 이벤트: {}건", allEvents.size());

        // ── 2) 과거 미완료 티켓 추가 조회 (최대 6개월) ───────────────────
        LocalDate overdueEnd = startDate.minusDays(1);
        LocalDate overdueStart = startDate.minusMonths(6);

        log.info("[DailyCalendarTicketCollector] 과거 미완료 이벤트 수집: {} ~ {}", overdueStart, overdueEnd);
        List<Event> overdueEvents = calendarClient.listEvents(
                calendarId,
                overdueStart + "T00:00:00+09:00",
                overdueEnd + "T23:59:59+09:00",
                null);
        log.info("[DailyCalendarTicketCollector] 과거 미완료 이벤트: {}건", overdueEvents.size());

        // ── 두 목록 병합 (이벤트 ID 기준 중복 제거) ──────────────────────
        Set<String> seenIds = new HashSet<>();
        List<Event> merged = new ArrayList<>();
        for (Event e : allEvents) {
            if (seenIds.add(e.getId())) merged.add(e);
        }
        for (Event e : overdueEvents) {
            if (seenIds.add(e.getId())) merged.add(e);
        }

        // ── 팀원별 결과 맵 초기화 ─────────────────────────────────────────
        LinkedHashMap<TeamMember, List<TicketItem>> result = new LinkedHashMap<>();
        members.forEach(m -> result.put(m, new ArrayList<>()));

        // ── 이벤트를 팀원별로 분류 ────────────────────────────────────────
        for (Event event : merged) {
            List<String> assigneeNames = parseAssigneeNames(titleOf(event));
            for (TeamMember member : members) {
                String calName = member.effectiveCalendarName();
                boolean isAssigned = assigneeNames.stream()
                        .anyMatch(n -> n.trim().equals(calName));
                if (isAssigned) {
                    try {
                        TicketItem item = parseTicketEvent(event, member, baseDate);
                        if (item != null) result.get(member).add(item);
                    } catch (Exception e) {
                        log.warn("[DailyCalendarTicketCollector] 파싱 실패: member={}, title={}",
                                calName, titleOf(event), e);
                    }
                }
            }
        }

        // ── 정렬 ──────────────────────────────────────────────────────────
        result.forEach((member, tickets) ->
                tickets.sort(Comparator
                        .comparingInt((TicketItem t) -> t.getDueDate() == null
                                ? Integer.MAX_VALUE
                                : (int) java.time.temporal.ChronoUnit.DAYS
                                .between(baseDate, t.getDueDate()))
                        .thenComparingInt(t -> t.getPriority().getOrder())));

        // ── Jira 상태 재검증 ──────────────────────────────────────────────
        refreshStatusFromJira(result, baseDate);

        return result;
    }

    // =========================================================================
    // 내부 — 이벤트 파싱
    // =========================================================================

    private TicketItem parseTicketEvent(Event event, TeamMember member, LocalDate baseDate) {
        String title = titleOf(event);

        Matcher keyMatcher = ISSUE_KEY_PATTERN.matcher(title);
        if (!keyMatcher.find()) {
            log.debug("[DailyCalendarTicketCollector] 이슈 키 없음, 건너뜀: '{}'", title);
            return null;
        }
        String issueKey = keyMatcher.group(1) != null ? keyMatcher.group(1) : keyMatcher.group(2);
        String projectKey = issueKey.contains("-")
                ? issueKey.substring(0, issueKey.lastIndexOf('-')) : "";

        List<String> assigneeNames = parseAssigneeNames(title);
        String memberCalName = member.effectiveCalendarName();
        boolean isAssigned = assigneeNames.isEmpty()
                || assigneeNames.stream().anyMatch(n -> n.trim().equals(memberCalName));
        if (!isAssigned) return null;

        LocalDate dueDate = dateOf(event);
        JiraPriorityCode priority = detectPriority(title);
        JiraStatusCode status = detectStatus(event.getDescription());
        DueDateUrgencyCode urgency = DueDateUrgencyCode.of(baseDate, dueDate);

        String url = (jiraBaseUrl != null ? jiraBaseUrl : "https://riman-it.atlassian.net")
                + "/browse/" + issueKey;

        String summary = extractTitleFromDescription(event.getDescription());
        if (summary == null || summary.isBlank()) summary = extractSummary(title);
        if (summary == null || summary.isBlank()) summary = issueKey;

        return TicketItem.builder()
                .issueKey(issueKey)
                .summary(summary)
                .projectKey(projectKey)
                .assigneeName(member.getName())
                .assigneeAccountId(member.getJiraAccountId() != null ? member.getJiraAccountId() : "")
                .status(status)
                .priority(priority)
                .dueDate(dueDate)
                .urgency(urgency)
                .url(url)
                .build();
    }

    private List<String> parseAssigneeNames(String title) {
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

    private String extractTitleFromDescription(String description) {
        if (description == null || description.isBlank()) return null;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Title: ")) {
                String t = trimmed.substring("Title: ".length()).trim();
                return t.isBlank() ? null : t;
            }
        }
        return null;
    }

    private String extractSummary(String title) {
        if (title == null) return "";
        String s = ISSUE_KEY_PATTERN.matcher(title).replaceAll("").trim();
        s = ASSIGNEE_PATTERN.matcher(s).replaceAll("").trim();
        s = s.replaceAll("[🔴🟠🟡🟢⚪⚫]", "").trim();
        return s;
    }

    private JiraPriorityCode detectPriority(String title) {
        if (title == null) return JiraPriorityCode.MEDIUM;
        if (title.contains("🔴")) return JiraPriorityCode.HIGHEST;
        if (title.contains("🟠")) return JiraPriorityCode.HIGH;
        if (title.contains("🟢")) return JiraPriorityCode.LOW;
        if (title.contains("⚪")) return JiraPriorityCode.LOWEST;
        return JiraPriorityCode.MEDIUM;
    }

    /**
     * description 의 "Status: ..." 라인에서 Jira 상태 감지.
     *
     * <p>Status 라인 없으면 IN_PROGRESS 반환.
     * 이벤트 제목 키워드 폴백은 제거 — 정상 제목 오탐 방지.
     */
    private JiraStatusCode detectStatus(String description) {
        if (description != null) {
            for (String line : description.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Status: ")) {
                    String statusName = trimmed.substring("Status: ".length()).trim();
                    return JiraStatusCode.fromStatusName(statusName);
                }
            }
        }
        return JiraStatusCode.IN_PROGRESS;
    }

    // =========================================================================
    // 내부 — Jira 상태 재검증
    // =========================================================================

    /**
     * description 에 Status 정보가 없어 IN_PROGRESS 로 분류된 과거 기한 티켓을
     * Jira API로 실제 상태를 확인하여 정정한다.
     *
     * <p>jiraClient 가 null 이면 건너뜀 (degraded mode).
     * 대상: dueDate &lt; 금주 월요일 AND status == IN_PROGRESS
     */
    private void refreshStatusFromJira(
            LinkedHashMap<TeamMember, List<TicketItem>> result, LocalDate baseDate) {

        if (jiraClient == null) {
            log.debug("[DailyCalendarTicketCollector] JiraClient 미설정, 상태 재검증 건너뜀");
            return;
        }

        LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        Set<String> targetKeys = result.values().stream()
                .flatMap(List::stream)
                .filter(t -> t.getDueDate() != null
                        && t.getDueDate().isBefore(thisMonday)
                        && t.getStatus() == JiraStatusCode.IN_PROGRESS)
                .map(TicketItem::getIssueKey)
                .collect(Collectors.toSet());

        if (targetKeys.isEmpty()) return;

        log.info("[DailyCalendarTicketCollector] Jira 상태 재검증 대상: {}건 {}", targetKeys.size(), targetKeys);

        Map<String, JiraStatusCode> actualStatusMap = fetchStatusFromJira(targetKeys);
        if (actualStatusMap.isEmpty()) return;

        result.forEach((member, tickets) -> {
            for (int i = 0; i < tickets.size(); i++) {
                TicketItem t = tickets.get(i);
                JiraStatusCode actual = actualStatusMap.get(t.getIssueKey());
                if (actual != null && actual != t.getStatus()) {
                    log.info("[DailyCalendarTicketCollector] 상태 정정: {} {} → {}",
                            t.getIssueKey(), t.getStatus(), actual);
                    tickets.set(i, t.toBuilder().status(actual).build());
                }
            }
        });
    }

    private Map<String, JiraStatusCode> fetchStatusFromJira(Set<String> issueKeys) {
        Map<String, JiraStatusCode> map = new HashMap<>();
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
            log.info("[DailyCalendarTicketCollector] Jira 배치 조회 완료: {}건", map.size());
        } catch (Exception e) {
            log.warn("[DailyCalendarTicketCollector] Jira 상태 재검증 실패, 건너뜀", e);
        }
        return map;
    }

    // =========================================================================
    // 내부 — 공통 헬퍼
    // =========================================================================

    private LocalDate dateOf(Event event) {
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

    private String titleOf(Event event) {
        return event.getSummary() != null ? event.getSummary() : "";
    }
}
