package com.riman.automation.worker.dto.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jira Webhook 이벤트 페이로드 모델.
 * Jira Cloud가 전송하는 webhook payload를 역직렬화한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWebhookEvent {

  // Lambda에서 추가하는 메타데이터
  private String eventId;
  private Instant receivedAt;

  // Jira webhook 필드
  private Long timestamp;

  /** 이벤트 유형. "jira:issue_created", "jira:issue_updated", "jira:issue_deleted". */
  @JsonProperty("webhookEvent")
  private String webhookEvent;

  @JsonProperty("issue_event_type_name")
  private String issueEventTypeName;

  private Issue issue;
  private Changelog changelog;
  private User user;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Issue {
    private String id;
    private String key;
    private String self;
    private Fields fields;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Fields {
    private String summary;
    private String description;
    private IssueType issuetype;
    private Priority priority;
    private Status status;
    private User assignee;
    private User reporter;
    private Project project;
    private String duedate;

    /**
     * Jira Start Date 커스텀 필드.
     * riman-it.atlassian.net 인스턴스에서는 customfield_10015로 운영된다.
     * 값 형식은 "yyyy-MM-dd" 또는 null이다.
     */
    @JsonProperty("customfield_10015")
    private String startdate;

    private String created;
    private String updated;

    /**
     * 실제 캘린더 시작일을 반환한다.
     * startdate가 있으면 그 값을, 없으면 duedate를 폴백으로 반환한다.
     */
    public String getEffectiveStartDate() {
      if (startdate != null && !startdate.isBlank()) {
        return startdate;
      }
      return duedate;
    }

    /** startdate가 실제로 설정되어 있는지 여부. */
    public boolean hasStartDate() {
      return startdate != null && !startdate.isBlank();
    }
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class IssueType {
    private String id;
    private String name;
    private String iconUrl;
    private Boolean subtask;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Priority {
    private String id;
    private String name;
    private String iconUrl;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Status {
    private String id;
    private String name;
    private StatusCategory statusCategory;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StatusCategory {
    /** "new", "indeterminate", "done". */
    private String key;
    private String colorName;
    private String name;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class User {
    private String accountId;
    private String emailAddress;
    private String displayName;
    private Map<String, String> avatarUrls;
    private Boolean active;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Project {
    private String id;
    private String key;
    private String name;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Changelog {
    private String id;
    private List<ChangeItem> items;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChangeItem {
    private String field;
    private String fieldtype;
    private String from;
    private String fromString;
    private String to;
    private String toString;
  }
}
