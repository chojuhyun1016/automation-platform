# Worker 모듈

SQS 메시지를 소비하여 Jira-Calendar 동기화, 재택/부재 캘린더 등록, Slack DM 알림을 수행하는 Lambda 모듈입니다.

## 모듈 개요

ingest 모듈이 SQS에 전달한 메시지를 처리하는 백엔드 워커로, 다음 역할을 수행합니다:

- **Jira 웹훅 처리**: Jira 이슈 변경 → Google Calendar 이벤트 동기화 + Slack DM 알림
- **재택근무 등록**: Slack `/재택근무` → Google Calendar 이벤트 생성
- **부재 등록**: Slack `/부재등록` → Google Calendar 이벤트 생성 + 그룹웨어 SQS 전달
- **일정 등록/삭제**: Slack `/일정등록` → Google Calendar 이벤트 CRUD + DynamoDB 매핑

## 패키지 구조

```
com.riman.automation.worker/
├── handler/
│   └── WorkerHandler              # Lambda 진입점 (SQS 이벤트)
├── facade/
│   ├── JiraEventFacade            # Jira 웹훅 이벤트 오케스트레이션
│   ├── RemoteWorkFacade           # 재택근무 캘린더 등록
│   ├── AbsenceFacade              # 부재 캘린더 등록 + 그룹웨어 연동
│   └── ScheduleEventFacade        # 일정 등록/삭제 (Calendar + DynamoDB)
├── service/
│   ├── CalendarService            # Google Calendar 이벤트 CRUD (Jira 연동)
│   ├── SlackNotificationService   # Slack DM 알림 (담당자 변경/기한/상태)
│   ├── JiraCalendarMappingService # DynamoDB: issueKey ↔ calendarEventId 매핑
│   ├── ScheduleEventMappingService # DynamoDB: slackUserId ↔ scheduleEventId 매핑
│   ├── GroupwareMessageService    # 그룹웨어 부재 SQS 발행
│   └── ConfigLoader               # S3 설정 파일 로드 (config.json, team-members.json)
└── dto/                           # 내부 DTO (SQS 메시지 구조)
```

## 의존성

### 내부 모듈

| 모듈 | 용도 |
|------|------|
| `common` | 예외 클래스, 코드 Enum, DateTimeUtil, SlackBlockBuilder |
| `clients` | JiraClient, SlackClient, GoogleCalendarClient |

### 외부 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `aws-lambda-java-core` / `aws-lambda-java-events` | Lambda 런타임 |
| AWS SDK v2: `sqs`, `s3`, `dynamodb`, `secretsmanager` | AWS 서비스 |
| `google-api-services-calendar` v3 | Google Calendar API |
| `jackson-databind` / `jackson-datatype-jsr310` | JSON 처리 |
| `slf4j-simple` | 로깅 |

## 주요 환경변수

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `CONFIG_BUCKET` | S3 버킷 (설정 파일) | O |
| `CONFIG_KEY` | S3 키 (config.json) | O |
| `TEAM_MEMBERS_KEY` | S3 키 (team-members.json) | O |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | S3 키 (Google 인증키) | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |
| `JIRA_EMAIL` | Jira 계정 이메일 | O |
| `JIRA_API_TOKEN` | Jira API 토큰 | O |
| `JIRA_BASE_URL` | Jira Base URL | O |
| `CALENDAR_MAPPING_TABLE` | DynamoDB 테이블명 (Jira-Calendar 매핑) | O |
| `SCHEDULE_MAPPING_TABLE` | DynamoDB 테이블명 (일정 매핑) | O |
| `GROUPWARE_SQS_QUEUE_URL` | 그룹웨어용 SQS 큐 URL | - |

## 핵심 흐름

### SQS 메시지 라우팅

```
SQS 메시지 수신 (WorkerHandler)
    ↓ messageType 분기
    ├─ jira_webhook      → JiraEventFacade
    ├─ remote_work       → RemoteWorkFacade
    ├─ absence           → AbsenceFacade
    └─ schedule_event    → ScheduleEventFacade
```

### Jira 이벤트 처리 (JiraEventFacade)

```
Jira 이슈 변경 이벤트
    ↓
1. 팀원 관련 이벤트인지 판별
2. Google Calendar 이벤트 동기화
   ├─ 매핑 조회 (DynamoDB → extendedProperties fallback)
   ├─ 이벤트 없으면 생성, 있으면 업데이트
   └─ DynamoDB 매핑 저장
3. Slack DM 알림
   ├─ 담당자 변경: from + to 2명 모두
   ├─ 기타 변경: 현재 담당자 (팀원만)
   └─ 비팀원 티켓 변경: 알림 없음
```

### 부재 등록 (AbsenceFacade)

```
SQS 메시지 (absence)
    ↓
1. Google Calendar 부재 이벤트 생성
2. (GROUPWARE_SQS_QUEUE_URL 설정 시) 그룹웨어 부재 SQS 메시지 발행
   └─ groupware Lambda → Fargate → 그룹웨어 자동 신청
```

### DynamoDB 테이블 설계

**JiraCalendarEventMapping**
- PK: `issueKey` (String) - 예: CCE-2339
- SK: `calendarId` (String) - Google Calendar ID
- 속성: `eventId`, `assigneeName`, `createdAt`, `updatedAt`

**ScheduleEventMapping**
- PK: `slackUserId` (String)
- SK: `eventId` (String) - Google Calendar Event ID
- 속성: `calendarId`, `title`, `startDate`, `endDate`, `createdAt`