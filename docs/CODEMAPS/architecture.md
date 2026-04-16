<!-- Generated: 2026-04-16 | Files scanned: 117 | Token estimate: ~900 -->
# Architecture Codemap

## System Overview

Java 17 Gradle multi-module + Python 3.11 AWS Serverless platform.
Slack slash commands, Jira webhooks, EventBridge schedules.

## Module Dependency Graph

```
common (18 files, 984 LOC)
  ↑
clients (8 files, 1,436 LOC)
  ↑
  ├── ingest   (25 files, 4,892 LOC)  Lambda — API Gateway proxy
  ├── worker   (24 files, 3,882 LOC)  Lambda — SQS consumer
  ├── scheduler(34 files, 7,236 LOC)  Lambda — EventBridge cron
  └── groupware(4 files, 471 LOC)     Lambda — SQS → ECS Fargate

groupware-bot (4 Python files, 1,213 LOC)  ECS Fargate — independent
```

## Request Flow

```
Slack Command → API Gateway → ingest Lambda → SlackFacade → SQS → worker Lambda → Calendar/DynamoDB
Jira Webhook  → API Gateway → ingest Lambda → JiraWebhookFacade → SQS → worker Lambda → Calendar/DynamoDB
EventBridge   → scheduler Lambda → Collectors → Formatters → Slack DM / Confluence / Excel
Worker SQS    → groupware Lambda → ECS Fargate → groupware-bot (Playwright)
```

## Lambda Entry Points

| Module | Handler Class | Trigger | Timeout |
|--------|--------------|---------|---------|
| ingest | IngestHandler | API Gateway (proxy) | 30s |
| worker | WorkerHandler | SQS (automation-queue) | 900s |
| worker | DlqAlertHandler | SQS (DLQ) | 30s |
| scheduler | SchedulerHandler | EventBridge (cron) | 900s |
| groupware | GroupwareHandler | SQS (groupware-queue) | 60s |

## Key Constraints

- Slack 3s limit: ingest must respond fast, heavy work → SQS delegation
- Static volatile caching: GoogleCalendarClient ~1200ms, S3Client ~300ms
- Always HTTP 200 to Slack: prevent retry loop
- handleRequest() return = HTTP response: no early response
