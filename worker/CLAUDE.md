# worker 모듈

SQS 소비자. Jira-Calendar 동기화, 부재/재택/일정 처리를 담당한다.

## 패키지 구조

```
com.riman.automation.worker
├── handler/    WorkerHandler (SQSEvent → Void), DlqAlertHandler (DLQ → Slack 알림)
├── facade/     AbsenceFacade, RemoteWorkFacade, ScheduleFacade, LunchCardFacade, JiraIssueFacade
├── dto/
│   ├── sqs/    RemoteWorkMessage, AbsenceMessage, ScheduleMessage, LunchCardMessage
│   ├── jira/   JiraWebhookEvent
│   └── s3/     TeamMember
├── payload/    JiraSlackMessageBuilder, SlackTimeHeaderBuilder
└── service/    CalendarService, ConfigService, DedupeService, TeamMemberService,
                JiraCalendarMappingService, ScheduleEventMappingService,
                SlackNotificationService, GroupwareMessageService,
                MonitoringAlertService, AbsenceService, RemoteWorkService,
                LunchCardService, LunchCardNotificationService
```

## 메시지 디스패치

```
WorkerHandler
├── messageType 결정: SQS Attribute → JSON body → 기본값 "jira_webhook"
├── remote_work   → RemoteWorkFacade
├── absence       → AbsenceFacade
├── schedule      → ScheduleFacade
├── lunch_card    → LunchCardFacade
└── (default)     → JiraIssueFacade
```

## 생성자 DI 체인

```java
ConfigService → CalendarService
             → AbsenceFacade(ConfigService, CalendarService, TeamMemberService, DedupeService)
             → RemoteWorkFacade(ConfigService, CalendarService)
             → ScheduleFacade(ConfigService, CalendarService, DedupeService, ScheduleEventMappingService)
             → LunchCardFacade(ConfigService, LunchCardService, LunchCardNotificationService, TeamMemberService, DedupeService)
             → JiraIssueFacade(CalendarService, SlackNotificationService)
```

**ConfigService를 한 번만 생성**하여 S3 이중 로딩 방지.

## CalendarService 핵심 로직

### Jira 이벤트 처리
- CREATE: 기존 확인 (DynamoDB hit → UPDATE 전환, 중복 방지)
- UPDATE: 담당자 변경 이력, 비팀원 전환 처리
- DELETE: DynamoDB + Calendar 이벤트 삭제

### DynamoDB 2계층 조회
1. `JiraCalendarMappingService.findMapping(issueKey, calendarId)` — 빠름
2. Fallback: `findJiraEventByIssueKey()` — extendedProperties 스캔
3. 성공 시 DynamoDB 자동 등록 (하위 호환)

### startDate 정규화
- null/blank → 오늘, startDate > dueDate → dueDate로 보정

## DynamoDB 테이블

| 서비스 | 테이블 | PK / SK | 용도 |
|--------|--------|---------|------|
| DedupeService | `DYNAMODB_TABLE` | eventId / timestamp | Jira 중복 방지 |
| DedupeService | `DYNAMODB_TABLE` | `{PREFIX}#{eventId}` / — | 기능별 중복 (REMOTE#, ABSENCE#, SCHEDULE#, LUNCH_CARD#) |
| JiraCalendarMappingService | `CALENDAR_MAPPING_TABLE` | issueKey / calendarId | Jira↔Calendar 매핑 |
| ScheduleEventMappingService | `SCHEDULE_MAPPING_TABLE` | slackUserId / eventId | 일정↔Calendar 매핑 |

## 캐싱 & TTL

| 서비스 | TTL | 캐시 대상 |
|--------|-----|----------|
| ConfigService | 5분 | S3 config.json |
| SlackNotificationService | 5분 | Slack Bot 토큰 (Secrets Manager) |
| TeamMemberService | 영구 | S3 team-members.json (Lambda 수명) |

## 이벤트 제목 규칙

| 유형 | summary |
|------|---------|
| Jira | `[Jira] CCE-1234 (홍길동)` |
| 재택 | `재택(홍길동)` |
| 부재 | `연차(홍길동)`, `오전 반차(김철수)` |
| 일정 | `[일정] 제목` |
| 점심카드 | `점심카드(홍길동)` |

## 싱글톤

- `GroupwareMessageService.getInstance()`: GROUPWARE_SQS_QUEUE_URL 미설정 시 비활성화
- 메시지에 **ID/PW 절대 포함 금지** — Fargate가 Secrets Manager 직접 조회
