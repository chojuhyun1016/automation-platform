<!-- Generated: 2026-04-16 | Files scanned: 117 | Token estimate: ~500 -->
# Data Codemap

## DynamoDB Tables

### JIRA_CALENDAR_MAPPING

```
PK: issueKey (String)     예: "PROJ-123"
SK: calendarId (String)   예: "primary" 또는 calendarId
Attributes:
  eventId      Calendar event ID
  summary      이벤트 제목
  assignee     담당자
  createdAt    생성 일시
```
Usage: worker/JiraCalendarMappingService — Jira issue ↔ Calendar event 매핑
Fallback: extendedProperties scan (레거시 데이터)

### SCHEDULE_MAPPING

```
PK: slackUserId (String)  예: "U1234567"
SK: eventId (String)       예: "abc123"
Attributes:
  calendarId   Calendar ID
  summary      일정 제목
  startDate    시작일
  endDate      종료일
  createdAt    생성 일시
```
Usage: ingest/ScheduleMappingQueryService, worker/ScheduleEventMappingService

### DEDUP_TABLE

```
PK: deduplicationKey (String)  예: "REMOTE#evt123", "ABSENCE#evt456"
TTL: expiresAt (Number)
```
Usage: worker/DedupeService — 이벤트 중복 방지

## Google Calendar Event Model

```
Event
  summary:    "[PROJ-123] 이슈 제목 (담당자)"
  start/end:  date (종일) 또는 dateTime
  extendedProperties.private:
    jiraIssueKey:  "PROJ-123"
    jiraStatus:    "In Progress"
    jiraPriority:  "High"
```
Usage: worker/CalendarService, scheduler/CalendarTicketParser

## S3 Config Schema (주요 필드)

### config.json
```json
{
  "absence": { "calendar_id": "..." },
  "remoteWork": { "calendar_id": "..." },
  "routing": { "PROJECT_KEY": { "calendar_id": "...", "slack_channel": "..." } },
  "notification": { "default_channel": "..." }
}
```

### team-members.json
```json
[{
  "name": "홍길동",
  "slackUserId": "U123",
  "jiraAccountId": "abc",
  "email": "..."
}]
```

## Data Flow Summary

```
Slack Command → ingest → SQS message (JSON)
  ↓
worker → Google Calendar API (CRUD)
       → DynamoDB (mapping/dedup)
       → Slack API (notification)

Jira Webhook → ingest → SQS → worker
  ↓
Calendar event sync + DynamoDB mapping

EventBridge → scheduler
  ↓
S3 (config) + Calendar + Jira + DynamoDB → Report → Slack DM / Confluence
```
