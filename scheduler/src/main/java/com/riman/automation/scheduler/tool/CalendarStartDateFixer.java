package com.riman.automation.scheduler.tool;

import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1회용 Google Calendar 데이터 보정 도구.
 *
 * <p>2026년 1~6월 Jira 티켓 이벤트의 Start Date / Due Date를 보정한다.
 *
 * <h3>보정 룰</h3>
 * <ol>
 *   <li>Due Date 텍스트 누락 → event.start.date(캘린더 이벤트 날짜)로 삽입</li>
 *   <li>Start Date(extendedProperties) null → dueDate로 설정</li>
 *   <li>Start Date > Due Date → dueDate로 보정</li>
 *   <li>description 텍스트에 "Start Date:", "Due Date:" 누락 시 삽입</li>
 * </ol>
 *
 * <h3>실행 방법</h3>
 * <pre>
 * ./gradlew :scheduler:shadowJar
 *
 * # dry-run (변경 없이 보정 대상만 출력)
 * java -cp scheduler/build/libs/automation-scheduler.jar \
 *   com.riman.automation.scheduler.tool.CalendarStartDateFixer dry-run
 *
 * # 실제 보정
 * java -cp scheduler/build/libs/automation-scheduler.jar \
 *   com.riman.automation.scheduler.tool.CalendarStartDateFixer fix
 * </pre>
 */
public class CalendarStartDateFixer {

    private static final String CALENDAR_ID =
            "ad935c8251a0992ec9352112b6811919d41d30c0dd95a92b36e71b106e9f2b7e@group.calendar.google.com";

    private static final String PROP_JIRA_ISSUE_KEY = "jiraIssueKey";
    private static final String PROP_JIRA_START_DATE = "jiraStartDate";

    private static final String[][] MONTHS = {
            {"2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-01"},
            {"2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z", "2026-02"},
            {"2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z", "2026-03"},
            {"2026-04-01T00:00:00Z", "2026-05-01T00:00:00Z", "2026-04"},
            {"2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z", "2026-05"},
            {"2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z", "2026-06"},
    };

    public static void main(String[] args) throws IOException {
        boolean dryRun = args.length == 0 || !"fix".equalsIgnoreCase(args[0]);

        System.out.println("════════════════════════════════════════════════════");
        System.out.println("  Calendar Start/Due Date Fixer");
        System.out.println("  Mode: " + (dryRun ? "DRY-RUN (변경 없음)" : "FIX (실제 수정)"));
        System.out.println("════════════════════════════════════════════════════");
        System.out.println();

        // Google credentials 로드 (로컬 config/)
        byte[] credentials = Files.readAllBytes(Path.of("config/google-credentials.json"));
        GoogleCalendarClient client = new GoogleCalendarClient(credentials);

        int totalProcessed = 0;
        int totalFixed = 0;
        int totalSkipped = 0;

        for (String[] month : MONTHS) {
            String timeMin = month[0];
            String timeMax = month[1];
            String label = month[2];

            List<Event> events = client.listEvents(CALENDAR_ID, timeMin, timeMax, null);
            System.out.printf("── %s: %d건 조회 ──────────────────────────────%n", label, events.size());

            for (Event event : events) {
                // Jira 이벤트만 대상 (jiraIssueKey 존재 여부)
                if (!isJiraEvent(event)) {
                    continue;
                }
                totalProcessed++;

                String issueKey = getExtProp(event, PROP_JIRA_ISSUE_KEY);

                // ── 1단계: Due Date 결정 ──
                // event.start.date = 캘린더 이벤트 날짜 (항상 존재)
                LocalDate eventDate = extractEventDate(event);
                if (eventDate == null) {
                    System.out.printf("  [SKIP] %s — 이벤트 날짜 파싱 불가%n", issueKey);
                    totalSkipped++;
                    continue;
                }

                String desc = event.getDescription() != null ? event.getDescription() : "";
                LocalDate dueDateInDesc = extractDateFromDescription(desc, "Due Date:");
                LocalDate dueDate = (dueDateInDesc != null) ? dueDateInDesc : eventDate;

                // ── 2단계: Start Date 결정 ──
                String startDateProp = getExtProp(event, PROP_JIRA_START_DATE);
                LocalDate startDate;
                if (startDateProp != null && !startDateProp.isBlank()) {
                    startDate = LocalDate.parse(startDateProp, DateTimeFormatter.ISO_LOCAL_DATE);
                } else {
                    // Start Date null → dueDate (dueDate는 1단계에서 항상 채워짐)
                    startDate = dueDate;
                }

                // ── 3단계: 역전 보정 ──
                if (startDate.isAfter(dueDate)) {
                    startDate = dueDate;
                }

                // ── 변경 필요 여부 판단 ──
                StringBuilder reason = new StringBuilder();

                // extendedProperties 변경 필요?
                boolean propNeedsFix = false;
                String finalStartStr = startDate.toString();
                if (startDateProp == null || startDateProp.isBlank()) {
                    reason.append("[startDate prop 누락→").append(finalStartStr).append("] ");
                    propNeedsFix = true;
                } else if (!startDateProp.equals(finalStartStr)) {
                    reason.append("[startDate 역전 보정:").append(startDateProp).append("→").append(finalStartStr).append("] ");
                    propNeedsFix = true;
                }

                // description 변경 필요?
                boolean descNeedsFix = false;
                boolean hasDueDateText = containsLine(desc, "Due Date:");
                boolean hasStartDateText = containsLine(desc, "Start Date:");

                if (!hasDueDateText) {
                    reason.append("[Due Date 텍스트 누락] ");
                    descNeedsFix = true;
                }
                if (!hasStartDateText) {
                    reason.append("[Start Date 텍스트 누락] ");
                    descNeedsFix = true;
                }

                // Start Date 텍스트 값이 보정값과 다른 경우
                if (hasStartDateText) {
                    LocalDate existingStartInDesc = extractDateFromDescription(desc, "Start Date:");
                    if (existingStartInDesc != null && !existingStartInDesc.equals(startDate)) {
                        reason.append("[Start Date 텍스트 보정:").append(existingStartInDesc).append("→").append(finalStartStr).append("] ");
                        descNeedsFix = true;
                    }
                }

                if (!propNeedsFix && !descNeedsFix) {
                    totalSkipped++;
                    continue;
                }

                totalFixed++;
                String dueDateStr = dueDate.toString();
                System.out.printf("  [%s] %s  due=%s, start=%s  %s%n",
                        dryRun ? "DRY-RUN" : "FIX", issueKey, dueDateStr, finalStartStr, reason);

                if (!dryRun) {
                    // extendedProperties 업데이트
                    if (propNeedsFix) {
                        Map<String, String> props = getExtProps(event);
                        props.put(PROP_JIRA_START_DATE, finalStartStr);

                        Event.ExtendedProperties extProps = event.getExtendedProperties() != null
                                ? event.getExtendedProperties()
                                : new Event.ExtendedProperties();
                        extProps.setPrivate(props);
                        event.setExtendedProperties(extProps);
                    }

                    // description 업데이트
                    if (descNeedsFix) {
                        String fixedDesc = fixDescription(desc, finalStartStr, dueDateStr);
                        event.setDescription(fixedDesc);
                    }

                    client.updateEvent(CALENDAR_ID, event.getId(), event);
                }
            }
        }

        System.out.println();
        System.out.println("════════════════════════════════════════════════════");
        System.out.printf("  Jira 이벤트: %d건, 보정: %d건, 스킵: %d건%n",
                totalProcessed, totalFixed, totalSkipped);
        System.out.println("════════════════════════════════════════════════════");
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private static boolean isJiraEvent(Event event) {
        return getExtProp(event, PROP_JIRA_ISSUE_KEY) != null;
    }

    private static String getExtProp(Event event, String key) {
        if (event.getExtendedProperties() == null) return null;
        if (event.getExtendedProperties().getPrivate() == null) return null;
        return event.getExtendedProperties().getPrivate().get(key);
    }

    private static Map<String, String> getExtProps(Event event) {
        if (event.getExtendedProperties() != null
                && event.getExtendedProperties().getPrivate() != null) {
            return new HashMap<>(event.getExtendedProperties().getPrivate());
        }
        return new HashMap<>();
    }

    private static LocalDate extractEventDate(Event event) {
        if (event.getStart() == null) return null;
        // 종일 이벤트
        if (event.getStart().getDate() != null) {
            String dateStr = event.getStart().getDate().toStringRfc3339();
            return LocalDate.parse(dateStr.substring(0, 10));
        }
        // 시간 이벤트
        if (event.getStart().getDateTime() != null) {
            String dateTimeStr = event.getStart().getDateTime().toStringRfc3339();
            return LocalDate.parse(dateTimeStr.substring(0, 10));
        }
        return null;
    }

    private static boolean containsLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.trim().startsWith(prefix)) return true;
        }
        return false;
    }

    private static LocalDate extractDateFromDescription(String description, String prefix) {
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String val = trimmed.substring(prefix.length()).trim();
                try {
                    return LocalDate.parse(val, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * description 텍스트에 Start Date / Due Date 삽입 또는 교체.
     *
     * <p>기존 description 포맷:
     * <pre>
     * Jira Ticket: CCE-2339
     * Title: ...
     *
     * Assignee: ...
     * Priority: ...
     * Status: ...
     * Project: ...
     * [Start Date: yyyy-MM-dd]  ← 있으면 교체, 없으면 삽입
     * [Due Date: yyyy-MM-dd]    ← 있으면 유지, 없으면 삽입
     *
     * View in Jira:
     * https://...
     * </pre>
     */
    private static String fixDescription(String desc, String startDate, String dueDate) {
        String[] lines = desc.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean foundStartDate = false;
        boolean foundDueDate = false;
        boolean inserted = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // 기존 Start Date 라인 → 보정값으로 교체
            if (trimmed.startsWith("Start Date:")) {
                result.append("Start Date: ").append(startDate).append("\n");
                foundStartDate = true;
                continue;
            }

            // 기존 Due Date 라인 → 유지
            if (trimmed.startsWith("Due Date:")) {
                result.append("Due Date: ").append(dueDate).append("\n");
                foundDueDate = true;
                continue;
            }

            // "View in Jira:" 직전 빈 줄 → 누락된 날짜 삽입 지점
            if (!inserted && trimmed.isEmpty()
                    && i + 1 < lines.length
                    && lines[i + 1].trim().startsWith("View in Jira:")) {
                if (!foundStartDate) {
                    result.append("Start Date: ").append(startDate).append("\n");
                    foundStartDate = true;
                }
                if (!foundDueDate) {
                    result.append("Due Date: ").append(dueDate).append("\n");
                    foundDueDate = true;
                }
                inserted = true;
            }

            result.append(lines[i]);
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        // View in Jira 없는 극단적 케이스 → 끝에 추가
        if (!foundStartDate) {
            result.append("\nStart Date: ").append(startDate);
        }
        if (!foundDueDate) {
            result.append("\nDue Date: ").append(dueDate);
        }

        return result.toString();
    }
}
