<!-- Generated: 2026-04-16 | Files scanned: 117 | Token estimate: ~600 -->
# Dependencies Codemap

## External Service Map

```
                    ┌─────────────────────────────────────────┐
                    │           automation-platform            │
                    └──────────────────┬──────────────────────┘
          ┌────────────┬──────────────┼──────────────┬────────────┐
          ▼            ▼              ▼              ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌────────┐ ┌──────────┐
    │  Slack   │ │  Jira    │ │Google Calendar│ │Confluence│ │Anthropic │
    │  API     │ │  Cloud   │ │    API        │ │  API    │ │  Claude  │
    └──────────┘ └──────────┘ └──────────────┘ └────────┘ └──────────┘
    Bearer       Basic         Service Account  Basic       API Key
    all modules  worker,sched  worker,sched,ing scheduler   scheduler
```

## AWS Service Usage

| Service | Module | Purpose |
|---------|--------|---------|
| Lambda | ingest, worker, scheduler, groupware | Compute runtime |
| API Gateway | ingest | HTTP proxy (Slack, Jira webhook) |
| SQS | ingest → worker, worker → groupware | Async message passing |
| S3 | all | Config files, credentials, Excel, screenshots |
| DynamoDB | worker, ingest, scheduler | Jira mapping, schedule mapping, dedup |
| KMS | groupware, ingest | Envelope encryption (AES-256-GCM) |
| Secrets Manager | all | Token/credential storage |
| ECS Fargate | groupware | Python Playwright container |
| ECR | groupware | Docker image registry |
| EventBridge | scheduler | Cron scheduling (daily/weekly/monthly) |

## Gradle Module Dependencies

```
root
├── common       → (no project deps)
├── clients      → project(':common')
├── ingest       → project(':common'), project(':clients')
├── worker       → project(':common'), project(':clients')
├── scheduler    → project(':common'), project(':clients')
└── groupware    → project(':common'), project(':clients')
```

groupware-bot: Python (Gradle 미포함), boto3, playwright, urllib

## Key External Libraries

| Library | Module | Purpose |
|---------|--------|---------|
| google-api-services-calendar | clients | Google Calendar API |
| httpclient5 | clients | HTTP client (Slack, Jira, Anthropic) |
| jackson-databind | all | JSON parsing |
| aws-lambda-java-core | all Lambda | Lambda handler interface |
| aws-sdk-v2 (s3, sqs, dynamodb, kms, ecs) | various | AWS service clients |
| apache-poi | scheduler | Excel generation |
| sentry-java | common | Error tracking |

## S3 Config Files (Runtime)

| File | Consumers | Purpose |
|------|-----------|---------|
| config.json | worker, ingest | Jira routing, calendar IDs |
| scheduler-config.json | scheduler | Report targets, formats |
| team-members.json | worker, scheduler, ingest | Slack-Jira member mapping |
| groupware-config.json | groupware | EKP URL, approval rules |
| announcements.json | scheduler | Daily report announcements |
| google-credentials.json | worker, scheduler, ingest | Service account key |
| rules/DAILY_REPORT_RULES.md | scheduler | AI prompt (daily) |
| rules/WEEKLY_REPORT_RULES.md | scheduler | AI prompt (weekly) |
