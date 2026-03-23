package com.riman.automation.ingest.dto.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jira Webhook Event Model
 *
 * <p><b>변경 사항:</b>
 * Fields에 {@code startdate} 필드 추가.
 * worker 패키지의 JiraWebhookEvent와 동일하게 유지.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWebhookEvent {

    // Lambda에서 추가하는 메타데이터
    private String eventId;
    private Instant receivedAt;

    // Jira Webhook 필드
    private Long timestamp;

    @JsonProperty("webhookEvent")
    private String webhookEvent;  // "jira:issue_created", "jira:issue_updated", "jira:issue_deleted"

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
         *
         * <p>이 Jira 인스턴스(riman-it.atlassian.net)에서 "Start date" 필드는
         * 커스텀 필드 {@code customfield_10015}로 운영된다.
         * (확인 근거: GET /rest/api/3/issue/CCE-1 및 CCE-2145 응답)
         *
         * <p>값 형식: "yyyy-MM-dd" (예: "2025-09-25") 또는 null.
         */
        @JsonProperty("customfield_10015")
        private String startdate;

        private String created;
        private String updated;

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
        private String key;  // "new", "indeterminate", "done"
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
