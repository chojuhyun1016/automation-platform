package com.riman.automation.scheduler.service.collect;

import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.jira.JiraClient;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.scheduler.dto.report.WeeklyReportData;
import com.riman.automation.scheduler.dto.report.WeeklyReportData.WeeklyTicketItem;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import com.riman.automation.scheduler.service.util.CalendarTicketParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 주간보고용 Google Calendar 기반 티켓 수집기
 *
 * <p><b>캘린더를 원천 데이터로 사용하는 이유:</b>
 * Jira에서 직접 수집하면 티켓 담당자가 타팀원으로 변경된 경우,
 * 우리 팀원이 수행한 작업 이력이 보고서에서 누락된다.
 * Google Calendar는 팀원이 담당했던 이력이 그대로 남으므로 실적 집계에 더 정확하다.
 *
 * <p><b>수집 전략 — 캘린더 2회 조회:</b>
 * <ol>
 *   <li><b>완료</b>: 전주(weekStart~weekEnd) 범위 중 Status=DONE인 것</li>
 *   <li><b>진행중</b>: quarterStart~quarterEnd 범위(분기 전체) 중 Status≠DONE이고
 *       startDate ≤ weekEnd(또는 startDate null)이며 [이슈] 태그가 없는 것</li>
 *   <li><b>이슈</b>: quarterStart~quarterEnd 범위(분기 전체) 중 [이슈] 태그 포함 미완료이고
 *       startDate ≤ weekEnd(또는 startDate null)인 것</li>
 * </ol>
 *
 * <p><b>진행중/이슈 startDate 필터 이유:</b>
 * 분기 전체를 조회하면 미래 예정 티켓(startDate > weekEnd)이 포함된다.
 * startDate가 weekEnd 이후인 티켓은 보고 기준주에 시작되지 않은 것이므로 제외한다.
 * startDate가 null인 이벤트(FIX-4 이전 생성)는 필터를 적용하지 않고 포함한다.
 */
@Slf4j
@RequiredArgsConstructor
public class WeeklyCalendarTicketCollector {

    private static final String LOG_TAG = "WeeklyTicketCollect";

    private final GoogleCalendarClient calendarClient;
    private final String jiraBaseUrl;

    /**
     * Jira API 클라이언트 (nullable).
     * null이면 상태 재검증 건너뜀 (degraded mode).
     */
    private final JiraClient jiraClient;

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * 주간보고 전체 티켓 수집.
     *
     * @param calendarId   티켓 캘린더 ID
     * @param members      팀원 목록
     * @param weekStart    전주 시작일 (월요일)
     * @param weekEnd      전주 종료일 (일요일) ← 진행중/이슈 startDate 필터 기준
     * @param quarterStart 분기 시작일 ← 진행중/이슈 조회 하한
     * @param quarterEnd   분기 종료일 ← 진행중/이슈 조회 상한 (분기 전체 조회)
     */
    public CollectResult collect(
            String calendarId,
            List<TeamMember> members,
            LocalDate weekStart, LocalDate weekEnd,
            LocalDate quarterStart, LocalDate quarterEnd) {

        if (calendarId == null || calendarId.isBlank()) {
            log.warn("[{}] ticket_calendar_id 미설정 — 빈 결과 반환", LOG_TAG);
            return CollectResult.empty();
        }
        if (members == null || members.isEmpty()) {
            log.warn("[{}] 팀원 목록 없음 — 빈 결과 반환", LOG_TAG);
            return CollectResult.empty();
        }

        Set<String> memberNames = members.stream()
                .map(TeamMember::effectiveCalendarName)
                .collect(Collectors.toSet());

        log.info("[{}] 수집 시작: members={}명, 완료={} ~ {}, 진행중/이슈={} ~ {} (startDate 필터: ≤ {})",
                LOG_TAG, members.size(), weekStart, weekEnd, quarterStart, quarterEnd, weekEnd);

        // ── 1) 전주 완료 ─────────────────────────────────────────────────────
        List<WeeklyTicketItem> doneItems =
                collectDone(calendarId, memberNames, weekStart, weekEnd, members);

        // ── 2) 분기 전체 조회 후 Java 레이어에서 필터링
        //    - startDate > weekEnd 이면 제외 (미래 예정 티켓)
        //    - [이슈] 태그 포함이면 진행중에서 제외 (이슈 섹션에서 별도 표시)
        List<WeeklyTicketItem> allQuarterItems =
                collectQuarter(calendarId, memberNames, quarterStart, quarterEnd, members);

        // 진행중: 미완료 + [이슈] 아닌 것 + startDate 필터
        List<WeeklyTicketItem> inProgressItems = allQuarterItems.stream()
                .filter(t -> t.getStatus() != JiraStatusCode.DONE)
                .filter(t -> !t.isIssue())
                .filter(t -> isStartDateWithinCutoff(t.getStartDate(), weekEnd))
                .collect(Collectors.toList());

        // 이슈: 미완료 + [이슈] 태그 + startDate 필터
        List<WeeklyTicketItem> issueItems = allQuarterItems.stream()
                .filter(t -> t.getStatus() != JiraStatusCode.DONE)
                .filter(WeeklyTicketItem::isIssue)
                .filter(t -> isStartDateWithinCutoff(t.getStartDate(), weekEnd))
                .collect(Collectors.toList());

        log.info("[{}] 완료: done={}건, inProgress={}건, issues={}건",
                LOG_TAG, doneItems.size(), inProgressItems.size(), issueItems.size());

        return CollectResult.of(
                groupByCategory(doneItems),
                groupByCategory(inProgressItems),
                groupByCategory(issueItems)
        );
    }

    // =========================================================================
    // 완료 수집 — 전주 범위
    // =========================================================================

    private List<WeeklyTicketItem> collectDone(
            String calendarId, Set<String> memberNames,
            LocalDate from, LocalDate to, List<TeamMember> members) {

        log.info("[{}] 완료 수집: {} ~ {}", LOG_TAG, from, to);
        List<Event> events = fetchEvents(calendarId, from, to);
        log.info("[{}] 완료 후보 이벤트: {}건", LOG_TAG, events.size());

        List<WeeklyTicketItem> all = parseEvents(events, memberNames, members);
        refreshStatusFromJira(all);

        List<WeeklyTicketItem> done = all.stream()
                .filter(t -> t.getStatus() == JiraStatusCode.DONE)
                .collect(Collectors.toList());

        log.info("[{}] 완료 확정: {}건 (후보 {}건)", LOG_TAG, done.size(), all.size());
        return done;
    }

    // =========================================================================
    // 분기 전체 조회 — 진행중/이슈 공통 원본 데이터
    // =========================================================================

    /**
     * 분기 전체(quarterStart~quarterEnd) 이벤트 조회 후 미완료 티켓 반환.
     * 진행중/이슈 필터링은 collect()에서 Java 레이어로 수행한다.
     */
    private List<WeeklyTicketItem> collectQuarter(
            String calendarId, Set<String> memberNames,
            LocalDate from, LocalDate to, List<TeamMember> members) {

        log.info("[{}] 분기 조회: {} ~ {}", LOG_TAG, from, to);
        List<Event> events = fetchEvents(calendarId, from, to);
        log.info("[{}] 분기 후보 이벤트: {}건", LOG_TAG, events.size());

        List<WeeklyTicketItem> all = parseEvents(events, memberNames, members);
        refreshStatusFromJira(all);

        // 완료 제외 (진행중 + 이슈 모두 포함된 미완료 목록)
        List<WeeklyTicketItem> notDone = all.stream()
                .filter(t -> t.getStatus() != JiraStatusCode.DONE)
                .collect(Collectors.toList());

        log.info("[{}] 분기 미완료: {}건 (전체 후보 {}건)", LOG_TAG, notDone.size(), all.size());
        return notDone;
    }

    // =========================================================================
    // 캘린더 조회
    // =========================================================================

    private List<Event> fetchEvents(String calendarId, LocalDate from, LocalDate to) {
        String timeMin = from + "T00:00:00+09:00";
        String timeMax = to + "T23:59:59+09:00";
        try {
            return calendarClient.listEvents(calendarId, timeMin, timeMax, null);
        } catch (Exception e) {
            log.error("[{}] 캘린더 조회 실패: {} ~ {}, err={}", LOG_TAG, from, to, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // 이벤트 파싱 — CalendarTicketParser 위임
    // =========================================================================

    private List<WeeklyTicketItem> parseEvents(
            List<Event> events, Set<String> memberNames, List<TeamMember> members) {

        Map<String, WeeklyTicketItem> dedupByKey = new LinkedHashMap<>();

        for (Event event : events) {
            try {
                String title = CalendarTicketParser.titleOf(event);
                List<String> assignees = CalendarTicketParser.parseAssigneeNames(title);

                // 팀원 담당 이벤트인지 확인
                boolean isTeamMember = !assignees.isEmpty()
                        && assignees.stream().anyMatch(memberNames::contains);
                if (!isTeamMember) continue;

                WeeklyTicketItem item = parseEvent(event, assignees, members);
                if (item == null) continue;

                // 중복 이슈 키: 먼저 파싱된 것 유지
                dedupByKey.putIfAbsent(item.getIssueKey(), item);

            } catch (Exception e) {
                log.warn("[{}] 이벤트 파싱 실패: title={}, err={}",
                        LOG_TAG, CalendarTicketParser.titleOf(event), e.getMessage());
            }
        }

        return new ArrayList<>(dedupByKey.values());
    }

    private WeeklyTicketItem parseEvent(Event event, List<String> assigneeNames,
                                        List<TeamMember> members) {
        String title = CalendarTicketParser.titleOf(event);

        String issueKey = CalendarTicketParser.extractIssueKey(title);
        if (issueKey == null) {
            log.debug("[{}] 이슈 키 없음, 건너뜀: '{}'", LOG_TAG, title);
            return null;
        }
        String projectKey = CalendarTicketParser.extractProjectKey(issueKey);

        String summary = CalendarTicketParser.extractSummary(event, title);
        String category = WeeklyReportData.detectCategory(projectKey, summary);
        if (category == null) {
            log.debug("[{}] 미분류 제외: key={}, project={}, summary={}",
                    LOG_TAG, issueKey, projectKey, summary);
            return null;
        }

        String url = (jiraBaseUrl != null ? jiraBaseUrl : "https://riman-it.atlassian.net")
                + "/browse/" + issueKey;

        return WeeklyTicketItem.builder()
                .issueKey(issueKey)
                .summary(summary)
                .assigneeName(CalendarTicketParser.resolveAssigneeName(assigneeNames, members))
                .status(CalendarTicketParser.detectStatus(event.getDescription()))
                .statusName(CalendarTicketParser.detectStatus(event.getDescription()).name())
                .startDate(CalendarTicketParser.detectStartDate(event))
                .dueDate(CalendarTicketParser.dateOf(event))
                .priority(CalendarTicketParser.detectPriority(event.getDescription()))
                .url(url)
                .category(category)
                .issue(WeeklyReportData.detectIssue(summary))
                .projectKey(projectKey)
                .build();
    }

    // =========================================================================
    // Jira 상태 재검증 — CalendarTicketParser 위임
    // =========================================================================

    private void refreshStatusFromJira(List<WeeklyTicketItem> items) {
        if (jiraClient == null) {
            log.debug("[{}] JiraClient 미설정 → 상태 재검증 건너뜀", LOG_TAG);
            return;
        }

        Set<String> targetKeys = items.stream()
                .filter(t -> t.getStatus() == JiraStatusCode.IN_PROGRESS)
                .map(WeeklyTicketItem::getIssueKey)
                .collect(Collectors.toSet());

        if (targetKeys.isEmpty()) return;

        log.info("[{}] Jira 상태 재검증: {}건 {}", LOG_TAG, targetKeys.size(), targetKeys);
        Map<String, JiraStatusCode> actualMap =
                CalendarTicketParser.fetchStatusFromJira(targetKeys, jiraClient, LOG_TAG);
        if (actualMap.isEmpty()) return;

        for (int i = 0; i < items.size(); i++) {
            WeeklyTicketItem t = items.get(i);
            JiraStatusCode actual = actualMap.get(t.getIssueKey());
            if (actual != null && actual != t.getStatus()) {
                log.info("[{}] 상태 정정: {} {} → {}", LOG_TAG, t.getIssueKey(), t.getStatus(), actual);
                items.set(i, t.toBuilder()
                        .status(actual)
                        .statusName(actual.name())
                        .build());
            }
        }
    }

    // =========================================================================
    // startDate 필터 헬퍼
    // =========================================================================

    /**
     * startDate 필터 — cutoff 이전이거나 null이면 포함.
     *
     * <p>startDate가 null인 이벤트는 FIX-4 이전 생성된 이벤트로,
     * 시작일 정보가 없으므로 제외하지 않고 포함한다.
     *
     * @param startDate 티켓 시작일 (null 허용)
     * @param cutoff    기준일 (주간=weekEnd, 월간=monthEnd)
     * @return startDate가 null이거나 cutoff 이하이면 true
     */
    private static boolean isStartDateWithinCutoff(LocalDate startDate, LocalDate cutoff) {
        return startDate == null || !startDate.isAfter(cutoff);
    }

    // =========================================================================
    // 카테고리 그룹핑
    // =========================================================================

    private Map<String, List<WeeklyTicketItem>> groupByCategory(List<WeeklyTicketItem> items) {
        Map<String, List<WeeklyTicketItem>> map = new LinkedHashMap<>();
        WeeklyReportData.CATEGORY_ORDER.forEach(cat -> map.put(cat, new ArrayList<>()));
        for (WeeklyTicketItem item : items) {
            List<WeeklyTicketItem> bucket = map.get(item.getCategory());
            if (bucket != null) bucket.add(item);
        }
        return map;
    }

    // =========================================================================
    // 결과 컨테이너
    // =========================================================================

    @lombok.Value
    public static class CollectResult {
        Map<String, List<WeeklyTicketItem>> doneByCategory;
        Map<String, List<WeeklyTicketItem>> inProgressByCategory;
        Map<String, List<WeeklyTicketItem>> issuesByCategory;

        public static CollectResult of(
                Map<String, List<WeeklyTicketItem>> done,
                Map<String, List<WeeklyTicketItem>> inProgress,
                Map<String, List<WeeklyTicketItem>> issues) {
            return new CollectResult(done, inProgress, issues);
        }

        public static CollectResult empty() {
            Map<String, List<WeeklyTicketItem>> e = new LinkedHashMap<>();
            WeeklyReportData.CATEGORY_ORDER.forEach(cat -> e.put(cat, new ArrayList<>()));
            return new CollectResult(
                    new LinkedHashMap<>(e),
                    new LinkedHashMap<>(e),
                    new LinkedHashMap<>(e));
        }
    }
}
