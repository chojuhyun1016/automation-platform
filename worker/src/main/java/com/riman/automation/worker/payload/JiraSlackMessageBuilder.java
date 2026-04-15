package com.riman.automation.worker.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Jira 웹훅 이벤트를 Slack chat.postMessage 페이로드 JSON으로 변환하는 순수 함수 유틸리티.
 * 우선순위/상태 이모지는 공통 Enum(JiraPriorityCode, JiraStatusCode)에 위임하여 중복 매핑을 피한다.
 */
public class JiraSlackMessageBuilder {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /** KST 기준 시각 포맷 ("오전/오후 h:mm"). */
  private static final DateTimeFormatter KST_TIME_FMT =
      DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
          .withZone(DateTimeUtil.KST);

  private JiraSlackMessageBuilder() {
  }

  /**
   * 채널 전송용 Slack 메시지 JSON을 생성한다.
   *
   * @param event   Jira 웹훅 이벤트
   * @param routing 프로젝트 라우팅 설정 (channelId 포함)
   * @return Slack chat.postMessage 페이로드 JSON
   */
  public static String formatChannelMessage(JiraWebhookEvent event, ProjectRouting routing)
      throws Exception {
    return objectMapper.writeValueAsString(
        buildPayload(routing.getSlackChannelId(), buildMessageText(event)));
  }

  /**
   * DM 전송용 Slack 메시지 JSON을 생성한다.
   *
   * @param event     Jira 웹훅 이벤트
   * @param recipient DM 수신자 팀 멤버 (slackUserId 포함)
   * @return Slack chat.postMessage 페이로드 JSON
   */
  public static String formatDmMessage(JiraWebhookEvent event, TeamMember recipient)
      throws Exception {
    return objectMapper.writeValueAsString(
        buildPayload(recipient.getSlackUserId(), buildMessageText(event)));
  }

  private static java.util.Map<String, Object> buildPayload(String channel, String text) {
    java.util.Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("channel", channel);
    payload.put("text", text);
    payload.put("unfurl_links", false);
    payload.put("unfurl_media", false);
    return payload;
  }

  /**
   * Slack 메시지 본문 텍스트를 구성한다.
   * 헤더(이벤트 유형 + 시각) → 티켓 정보(담당자/우선순위/마감일/상태) → 프로젝트/보고자 → 변경 이력 순서.
   */
  private static String buildMessageText(JiraWebhookEvent event) {
    JiraWebhookEvent.Issue issue = event.getIssue();
    JiraWebhookEvent.Fields fields = issue.getFields();

    StringBuilder message = new StringBuilder();

    // 헤더: 이벤트 유형 + 현재 KST 시각
    String emoji = getEventEmoji(event.getWebhookEvent());
    String eventText = getEventText(event.getWebhookEvent());
    String timeText = KST_TIME_FMT.format(DateTimeUtil.nowKst()
        .atZone(DateTimeUtil.KST));

    message.append(emoji)
        .append(" *").append(eventText).append("* | ")
        .append(timeText)
        .append("\n\n");

    // 티켓 정보
    String jiraUrl = buildJiraUrl(issue.getKey());
    String ticketLine = issue.getKey() + " " + fields.getSummary();
    message.append(":ticket: <").append(jiraUrl).append("|").append(ticketLine).append(">");

    String assigneeName = fields.getAssignee() != null
        ? fields.getAssignee().getDisplayName() : "Unassigned";
    message.append(" | :bust_in_silhouette: ").append(assigneeName);

    String priorityName = fields.getPriority() != null ? fields.getPriority().getName() : "";
    String priorityEmoji = JiraPriorityCode.from(priorityName).getEmoji();
    message.append(" | ").append(priorityEmoji).append(" ").append(priorityName);

    if (fields.getDuedate() != null) {
      message.append(" | :calendar: ").append(fields.getDuedate());
    }

    String statusName = fields.getStatus() != null ? fields.getStatus().getName() : "";
    String statusEmoji = getStatusEmoji(statusName);
    message.append(" | ").append(statusEmoji).append(" ").append(statusName);

    message.append("\n\n");

    // 프로젝트 / 보고자
    String reporterName = fields.getReporter() != null
        ? fields.getReporter().getDisplayName() : "Unknown";
    message.append(":file_folder: *Project:* ").append(fields.getProject().getName());
    message.append(" | :pencil2: *Reporter:* ").append(reporterName);

    // 변경 이력 (issue_updated 이벤트 한정)
    if ("jira:issue_updated".equals(event.getWebhookEvent())
        && event.getChangelog() != null) {
      message.append("\n\n:arrows_counterclockwise: *Changes:*\n");
      for (JiraWebhookEvent.ChangeItem item : event.getChangelog().getItems()) {
        if ("description".equalsIgnoreCase(item.getField())) continue;
        String fromValue = item.getFromString() != null ? item.getFromString() : "None";
        String toValue = item.getToString() != null ? item.getToString() : "None";
        message.append("  • *").append(item.getField()).append(":* `")
            .append(fromValue).append("` -> `").append(toValue).append("`\n");
      }
    }

    return message.toString();
  }

  private static String getEventEmoji(String webhookEvent) {
    switch (webhookEvent) {
      case "jira:issue_created":
        return ":sparkles:";
      case "jira:issue_updated":
        return ":arrows_counterclockwise:";
      case "jira:issue_deleted":
        return ":wastebasket:";
      default:
        return ":memo:";
    }
  }

  private static String getEventText(String webhookEvent) {
    switch (webhookEvent) {
      case "jira:issue_created":
        return "티켓 생성";
      case "jira:issue_updated":
        return "티켓 업데이트";
      case "jira:issue_deleted":
        return "티켓 삭제";
      default:
        return "Issue Event";
    }
  }

  /**
   * Jira 상태명으로 Slack 이모지를 결정한다.
   * JiraStatusCode의 DONE/IN_PROGRESS 2값 체계를 우선 적용하고, IN_PROGRESS 계열은 세분화한다.
   */
  private static String getStatusEmoji(String statusName) {
    if (statusName == null || statusName.isBlank()) return ":radio_button:";

    JiraStatusCode code = JiraStatusCode.fromStatusName(statusName);
    if (code == JiraStatusCode.DONE) return ":white_check_mark:";

    String lower = statusName.toLowerCase();
    if (lower.contains("to do") || lower.contains("todo") || lower.contains("open")) {
      return ":bar_chart:";
    } else if (lower.contains("in progress") || lower.contains("doing")) {
      return ":construction:";
    } else if (lower.contains("blocked")) {
      return ":no_entry_sign:";
    }
    return ":radio_button:";
  }

  private static String buildJiraUrl(String issueKey) {
    return "https://riman-it.atlassian.net/browse/" + issueKey;
  }
}
