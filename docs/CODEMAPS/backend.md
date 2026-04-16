<!-- Generated: 2026-04-16 | Files scanned: 113 | Token estimate: ~950 -->
# Backend Codemap

## ingest Module (API Gateway → Slack/Jira routing)

```
IngestHandler.handleRequest(Map)
  ├─ /warmup → 200 "warm"
  ├─ /slack/* → SlackFacade.handle()
  │   ├─ Retry detection (X-Slack-Retry-Num + Reason)
  │   ├─ payload= → Interaction (callback_id routing)
  │   │   ├─ absence_submit      → WorkerMessageService.sendAbsence() [SQS]
  │   │   ├─ remote_work_submit  → WorkerMessageService.sendRemoteWork() [SQS]
  │   │   ├─ account_manage      → AccountManageFacade (KMS encrypt, no SQS)
  │   │   ├─ schedule_submit     → WorkerMessageService.sendSchedule() [SQS]
  │   │   └─ current_ticket      → CurrentTicketFacade (Calendar query, no SQS)
  │   └─ Slash command → open modal (ModalBuilder)
  └─ /webhook/jira → JiraWebhookFacade → SQS

Key Services:
  SlackApiService         shared SlackClient (injected to all facades)
  WorkerMessageService    singleton, static SqsClient
  PasswordEncryptionService  KMS AES-256
  ScheduleMappingQueryService  DynamoDB pre-warm (DescribeTable)
  SlackSignatureVerifier  HMAC-SHA256, 5min window
```

## worker Module (SQS → Business Logic)

```
WorkerHandler.handleRequest(SQSEvent)
  ├─ messageType dispatch:
  │   ├─ "remote_work" → RemoteWorkFacade → CalendarService
  │   ├─ "absence"     → AbsenceFacade → CalendarService + GroupwareMessageService
  │   ├─ "schedule"    → ScheduleFacade → CalendarService + ScheduleEventMappingService
  │   └─ default       → JiraIssueFacade → CalendarService + JiraCalendarMappingService
  └─ DlqAlertHandler → MonitoringAlertService → Slack alert

Key Services:
  CalendarService              Google Calendar CRUD (CREATE/UPDATE/DELETE)
  ConfigService                S3 config loader (5min TTL)
  DedupeService                DynamoDB duplicate prevention
  JiraCalendarMappingService   2-layer lookup (DynamoDB → extendedProperties fallback)
  TeamMemberService            S3 team-members.json (full lifetime cache)
  SlackNotificationService     Slack DM + channel (TTL 5min bot token)
```

## scheduler Module (EventBridge → Reports)

```
SchedulerHandler.handleRequest(Map)
  ├─ "daily"   → DailyReportFacade
  │   └─ Collectors(4) → DailyReportFormatter → DailyReportService → Slack DM
  ├─ "weekly"  → WeeklyReportFacade
  │   └─ Collectors(1) → WeeklyReportFormatter → WeeklyReportService → Confluence + Excel + Slack
  └─ "monthly" → MonthlyReportFacade
      └─ Collectors(1) → MonthlyReportFormatter → MonthlyReportService → Confluence + Excel + Slack

Pipeline: Load Config → Collect Data → Format → Send

Collectors (6):
  DailyAbsenceCollector         2 calendars merged (eventId dedup)
  DailyCalendarTicketCollector  Calendar + Jira (assignee name matching)
  DailyJiraTicketCollector      Jira JQL (not done, this week)
  DailyScheduleCollector        DynamoDB + Calendar cross-match
  WeeklyCalendarTicketCollector done(week) + in-progress(quarter)
  MonthlyCalendarTicketCollector done(month) + in-progress(quarter)
```

## groupware Module (SQS → ECS Fargate)

```
GroupwareHandler.handleRequest(SQSEvent)
  └─ GroupwareAbsenceFacade
      ├─ Load groupware-config.json (approval_rules)
      ├─ Resolve approver
      └─ EcsTaskService.runTask() → Fargate (groupware-bot)
          ├─ secrets_client.py (KMS envelope decrypt)
          ├─ groupware_client.py (Playwright browser automation)
          └─ slack_notifier.py (result DM)
```

## common Module (Shared Library)

```
auth/     TokenProvider (interface), BasicTokenProvider, EnvTokenProvider
code/     AbsenceTypeCode, WorkStatusCode, JiraStatusCode, JiraPriorityCode,
          DueDateUrgencyCode, ReportPeriodCode, ReportWeekCode
exception/ AutomationException, ConfigException, ExternalApiException, ExternalApiClientException
util/     DateTimeUtil (todayKst, nowKst), SentryInitializer
slack/    SlackBlockBuilder (Block Kit DSL)
model/    GroupwareAccountInfo
```

## clients Module (External API Wrappers)

```
http/     SharedHttpClient (singleton), BaseHttpClient (abstract), ApiResponse
slack/    SlackClient (postMessage, openView, updateView, openDm)
jira/     JiraClient (search via POST /rest/api/3/search/jql, auto pagination)
calendar/ GoogleCalendarClient (query, insert, update, delete) ~1200ms init
confluence/ ConfluenceClient (3-step search, upsertPage via HttpURLConnection)
anthropic/ AnthropicClient (complete, claude-sonnet model)
```
