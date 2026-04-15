package com.riman.automation.ingest.dto.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jira Webhook 이벤트 모델.
 * worker 패키지의 동명 모델과 구조를 동일하게 유지하며 SQS 전달 대상 페이로드로 사용된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWebhookEvent {

  // Lambda에서 추가하는 메타데이터.
  private String eventId;
  private Instant receivedAt;

  // Jira Webhook 원본 필드.
  private Long timestamp;

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
     * Jira Start Date.
     * riman-it.atlassian.net 환경에서는 커스텀 필드 {@code customfield_10015} 로 운영된다.
     * 값 형식은 "yyyy-MM-dd" 이며 미설정 시 null 이다.
     */
    @JsonProperty("customfield_10015")
    private String startdate;

    private String created;
    private String updated;

    /**
     * startdate 우선, 없으면 duedate 를 반환한다.
     */
    public String getEffectiveStartDate() {
      if (startdate != null && !startdate.isBlank()) {
        return startdate;
      }
      return duedate;
    }

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
