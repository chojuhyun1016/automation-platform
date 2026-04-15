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
 * 1회용 Google Calendar 데이터 보정 도구이다.
 * 2026년 1~6월 Jira 티켓 이벤트의 Start Date 및 Due Date를 보정한다.
 * 보정 룰은 Due Date 텍스트가 누락되면 event.start.date로 삽입,
 * Start Date(extendedProperties)가 null이면 dueDate로 설정, Start Date가 Due Date보다 크면 dueDate로 보정,
 * description에 Start Date 또는 Due Date 라인이 누락되면 삽입하는 순이다.
 * 실행은 dry-run 모드(변경 없이 대상만 출력)와 fix 모드(실제 보정)로 나뉘며 shadowJar 실행 후 CLI 인자로 선택한다.
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
        if (!isJiraEvent(event)) {
          continue;
        }
        totalProcessed++;

        String issueKey = getExtProp(event, PROP_JIRA_ISSUE_KEY);

        LocalDate eventDate = extractEventDate(event);
        if (eventDate == null) {
          System.out.printf("  [SKIP] %s — 이벤트 날짜 파싱 불가%n", issueKey);
          totalSkipped++;
          continue;
        }

        String desc = event.getDescription() != null ? event.getDescription() : "";
        LocalDate dueDateInDesc = extractDateFromDescription(desc, "Due Date:");
        LocalDate dueDate = (dueDateInDesc != null) ? dueDateInDesc : eventDate;

        String startDateProp = getExtProp(event, PROP_JIRA_START_DATE);
        LocalDate startDate;
        if (startDateProp != null && !startDateProp.isBlank()) {
          startDate = LocalDate.parse(startDateProp, DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
          startDate = dueDate;
        }

        if (startDate.isAfter(dueDate)) {
          startDate = dueDate;
        }

        StringBuilder reason = new StringBuilder();

        boolean propNeedsFix = false;
        String finalStartStr = startDate.toString();
        if (startDateProp == null || startDateProp.isBlank()) {
          reason.append("[startDate prop 누락→").append(finalStartStr).append("] ");
          propNeedsFix = true;
        } else if (!startDateProp.equals(finalStartStr)) {
          reason.append("[startDate 역전 보정:").append(startDateProp).append("→").append(finalStartStr).append("] ");
          propNeedsFix = true;
        }

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
          if (propNeedsFix) {
            Map<String, String> props = getExtProps(event);
            props.put(PROP_JIRA_START_DATE, finalStartStr);

            Event.ExtendedProperties extProps = event.getExtendedProperties() != null
                ? event.getExtendedProperties()
                : new Event.ExtendedProperties();
            extProps.setPrivate(props);
            event.setExtendedProperties(extProps);
          }

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
    if (event.getStart().getDate() != null) {
      String dateStr = event.getStart().getDate().toStringRfc3339();
      return LocalDate.parse(dateStr.substring(0, 10));
    }
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
   * description 텍스트에 Start Date와 Due Date를 삽입하거나 교체한다.
   * 기존 Start Date 라인이 있으면 보정값으로 교체하고, Due Date 라인은 유지하며,
   * 라인이 없으면 "View in Jira:" 직전에 삽입한다. "View in Jira:"가 없으면 문자열 끝에 추가한다.
   */
  private static String fixDescription(String desc, String startDate, String dueDate) {
    String[] lines = desc.split("\n", -1);
    StringBuilder result = new StringBuilder();
    boolean foundStartDate = false;
    boolean foundDueDate = false;
    boolean inserted = false;

    for (int i = 0; i < lines.length; i++) {
      String trimmed = lines[i].trim();

      if (trimmed.startsWith("Start Date:")) {
        result.append("Start Date: ").append(startDate).append("\n");
        foundStartDate = true;
        continue;
      }

      if (trimmed.startsWith("Due Date:")) {
        result.append("Due Date: ").append(dueDate).append("\n");
        foundDueDate = true;
        continue;
      }

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

    if (!foundStartDate) {
      result.append("\nStart Date: ").append(startDate);
    }
    if (!foundDueDate) {
      result.append("\nDue Date: ").append(dueDate);
    }

    return result.toString();
  }
}
