# CLAUDE.md

이 파일은 Claude Code가 프로젝트 작업 시 자동으로 참조하는 컨텍스트 파일입니다.

## 프로젝트 개요

**automation-platform**은 Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄을 기반으로 팀 업무를 자동화하는 AWS 서버리스 플랫폼입니다.

- **언어**: Java 17 (Gradle 멀티모듈) + Python 3.11 (groupware-bot)
- **런타임**: AWS Lambda (Java), AWS Fargate (Python Docker)
- **리전**: ap-northeast-2 (서울)

## 모듈 구조

```
automation-platform/
├── common/          # 공통 라이브러리 (예외, Enum, 유틸리티, SlackBlockBuilder)
├── clients/         # 외부 API 클라이언트 (Jira, Slack, Calendar, Confluence, Anthropic)
├── ingest/          # Lambda 진입점 (Slack 커맨드, Jira 웹훅 수신)
├── worker/          # SQS 소비자 (Jira-Calendar 동기화, 재택/부재/일정 처리)
├── scheduler/       # EventBridge 스케줄러 (일일/주간/월간 보고서)
├── groupware/       # Lambda 오케스트레이터 (그룹웨어 부재 신청)
├── groupware-bot/   # Python Playwright (브라우저 자동화, Gradle 미포함)
└── config/          # S3 업로드용 런타임 설정 파일 (상세: config/README.md)
```

## 의존성 방향

```
common ← clients ← ingest / worker / scheduler / groupware
```

- `common`과 `clients`는 하위 모듈이므로 상위 모듈(ingest, worker 등)의 코드를 참조하면 안 됨
- `groupware-bot`은 독립적인 Python 프로젝트 (Gradle 멀티모듈에 포함되지 않음)

## Slack 슬래시 커맨드

| 커맨드 | 모듈 | Facade | 기능 |
|--------|------|--------|------|
| `/부재등록` | ingest → worker | AbsenceFacade | 부재 캘린더 등록 + 그룹웨어 연동 |
| `/재택근무` | ingest → worker | RemoteWorkFacade | 재택근무 캘린더 등록 |
| `/계정관리` | ingest | AccountManageFacade | 그룹웨어 계정 암호화 관리 |
| `/일정등록` | ingest → worker | ScheduleManageFacade | 일정 캘린더 CRUD + DynamoDB 매핑 |
| `/현재티켓` | ingest | CurrentTicketFacade | 담당 Jira 티켓 현황 조회 |

## Lambda 함수명 및 핸들러

| 모듈 | Lambda 함수명 | 핸들러 클래스 |
|------|--------------|--------------|
| ingest | `AutomationWebhookIngest` | IngestHandler |
| worker | `AutomationWebhookWorker` | WorkerHandler |
| scheduler | `AutomationScheduler` | SchedulerHandler |
| groupware | `automation-groupware` | GroupwareHandler |

## 빌드 명령

```bash
# 전체 빌드 (shadow JAR)
make build

# 모듈별 빌드
make build-ingest
make build-worker
make build-scheduler
make build-groupware

# groupware-bot Docker 빌드
make build-bot

# 전체 배포
make deploy-all

# 모듈별 배포
make deploy-ingest
make deploy-worker
make deploy-scheduler
make deploy-groupware
make push-bot

# 모듈별 컴파일 검증 (빠른 확인)
./gradlew :moduleName:compileJava
```

## 코딩 컨벤션

### Java

- **Java 17** 문법 사용 (record, sealed class, text block, pattern matching 등)
- **Lombok** 사용: `@Slf4j`, `@Getter`, `@Builder`, `@RequiredArgsConstructor`
- **패키지 구조**: `com.riman.automation.<module>.<layer>.<class>`
  - layer: `handler`, `facade`, `service`, `dto`, `payload`, `security`, `util`
  - scheduler 추가 layer: `collect`, `format`, `excel`, `load`, `report`, `tool`
- **예외 처리**: `common` 모듈의 예외 클래스 사용
  - `AutomationException` — 기본 비즈니스 예외 (unchecked, errorCode 포함)
  - `ConfigException` — S3 설정/환경변수 오류
  - `ExternalApiException` — HTTP 응답 오류 (4xx/5xx, Slack ok:false)
  - `ExternalApiClientException` — 통신 실패 (연결/파싱 오류, HTTP 응답 이전 단계)
- **로깅**: SLF4J (`@Slf4j`) 사용, `log.info/warn/error`
- **JSON**: Jackson (`ObjectMapper`) 사용, `jackson-datatype-jsr310`으로 Java Time 지원
- **날짜/시간**: `java.time` API 사용 (LocalDate, LocalDateTime, ZonedDateTime), KST 기준
- **Null 처리**: 방어적 null 체크 (`if (value != null)`), Optional은 반환타입에만 제한적 사용

### Python (groupware-bot)

- **Python 3.11**
- **Playwright** (Chromium headless)
- **boto3** (AWS SDK)
- **asyncio** 사용하지 않음 (동기 방식)

### 공통

- 커밋 메시지: 한국어 또는 영어, 간결하게
- 환경변수명: `UPPER_SNAKE_CASE`
- S3 설정 키: `kebab-case.json`

## common 모듈 주요 코드

### 예외 클래스 (`common/exception/`)

| 클래스 | 용도 |
|--------|------|
| `AutomationException` | 기본 비즈니스 예외 (errorCode 포함) |
| `ConfigException` | S3 설정/환경변수 로드 실패 |
| `ExternalApiException` | 외부 API HTTP 응답 오류 (statusCode 포함) |
| `ExternalApiClientException` | 외부 API 통신 실패 (연결/타임아웃/파싱) |

### Enum 코드 (`common/code/`)

| Enum | 용도 | 주요 값 |
|------|------|---------|
| `JiraStatusCode` | Jira 상태 (2값) | `IN_PROGRESS`, `DONE` |
| `JiraPriorityCode` | Jira 우선순위 | `HIGHEST`🔴, `HIGH`🟠, `MEDIUM`🟡, `LOW`🟢, `LOWEST`⚪ |
| `DueDateUrgencyCode` | 마감일 긴급도 | `OVERDUE`🔴, `URGENT`🔵(3일이내), `NORMAL`⚫, `NONE`⬜ |
| `WorkStatusCode` | 근무 상태 감지 | `OFFICE`, `REMOTE`, `ANNUAL_LEAVE`, `HALF_AM/PM` 등 |
| `AbsenceTypeCode` | 부재 유형 | `ANNUAL_LEAVE`, `SICK_LEAVE`, `AM_HALF`, `PM_HALF` 등 |
| `ReportWeekCode` | 주간 범위 | `THIS_WEEK`, `THIS_AND_NEXT_WEEK` (금요일 확장) |
| `ReportPeriodCode` | 보고서 기간 | `DAILY`, `WEEKLY`, `MONTHLY` |

### 유틸리티

| 클래스 | 용도 |
|--------|------|
| `DateTimeUtil` | 날짜 파싱/포맷 (ISO 형식, KST) |
| `SlackBlockBuilder` | Slack Block Kit JSON 빌더 |

## AWS 서비스 사용

| 서비스 | 용도 |
|--------|------|
| Lambda | ingest, worker, scheduler, groupware 모듈 실행 |
| API Gateway | Slack 커맨드, Jira 웹훅 수신 (동기 프록시 통합) |
| SQS | ingest → worker, worker → groupware 비동기 메시지 전달 |
| S3 | 설정 파일, Lambda JAR 배포, 스크린샷 저장 |
| DynamoDB | Jira-Calendar 매핑, 일정 매핑, 중복 방지 |
| Secrets Manager | Slack Bot Token, 그룹웨어 계정 정보 |
| KMS | 그룹웨어 비밀번호 AES-256-GCM 암호화 |
| ECS Fargate | groupware-bot Docker 컨테이너 실행 |
| ECR | groupware-bot Docker 이미지 저장 |
| EventBridge | Scheduler Lambda 정기 트리거 |

### SQS 큐

| 환경변수 | 방향 | 메시지 타입 |
|---------|------|------------|
| `SQS_QUEUE_URL` | ingest → worker | AbsenceMessage, RemoteWorkMessage, ScheduleMessage, JiraWebhookEvent |
| `GROUPWARE_SQS_QUEUE_URL` | worker → groupware | GroupwareAbsenceMessage |

### DynamoDB 테이블

| 환경변수 | 용도 | PK / SK |
|---------|------|---------|
| `CALENDAR_MAPPING_TABLE` | Jira issueKey ↔ Calendar eventId 매핑 | issueKey / calendarId |
| `SCHEDULE_MAPPING_TABLE` | `/일정등록` 이벤트 매핑 | slackUserId / eventId |
| `DYNAMODB_TABLE` | Jira 웹훅 중복 방지 (DedupeService) | webhookEventId |

## 외부 API

| API | 클라이언트 | 인증 방식 |
|-----|----------|----------|
| Slack Web API | `SlackClient` | Bearer Token (Bot Token) |
| Jira REST API v3 | `JiraClient` | Basic Auth (email + API token) |
| Google Calendar API v3 | `GoogleCalendarClient` | 서비스 계정 (google-credentials.json) |
| Confluence REST API | `ConfluenceClient` | Basic Auth (Atlassian 계정 공유) |
| Anthropic Claude API | `AnthropicClient` | API Key (x-api-key 헤더) |

## Google Calendar 이벤트 데이터 모델

Jira 티켓은 캘린더에 종일 이벤트로 저장된다.

### 캘린더 이벤트 ↔ Jira 필드 매핑

| 캘린더 필드 | 값 | 비고 |
|------------|---|------|
| `event.start.date` | Jira duedate | 캘린더 표시 날짜 = 마감일 |
| `event.end.date` | duedate + 1일 | Google 종일 이벤트 exclusive end 규칙 |
| `event.summary` | `[Jira] CCE-1234 (담당자)` | 이슈키 + 담당자명 |
| `event.description` | 구조화된 텍스트 | 아래 포맷 참조 |

### extendedProperties.private 키

| 키 | 값 예시 | 용도 |
|---|--------|------|
| `jiraIssueKey` | `"CCE-2339"` | 이슈 식별자 |
| `assigneeName` | `"홍길동"` | 담당자명 |
| `isTeamMember` | `"true"` | 팀원 여부 |
| `jiraStartDate` | `"2026-02-10"` | Jira 시작일 (yyyy-MM-dd) |

### description 포맷

```
Jira Ticket: CCE-2339
Title: 티켓 제목

Assignee: 홍길동
Priority: High
Status: In Progress
Project: CCE
Start Date: 2026-02-10
Due Date: 2026-02-15

View in Jira:
https://riman-it.atlassian.net/browse/CCE-2339
```

### startDate 방어 로직 (CalendarService.normalizeStartDate)

티켓 생성/업데이트 시 자동 적용:
- startDate가 null → 오늘 날짜로 대체
- startDate > dueDate → dueDate로 보정

## 설정 파일 (config/)

S3에 업로드되어 런타임에 사용됨. 상세 구조는 `config/README.md` 참조.

| 파일 | 용도 | 사용 모듈 |
|------|------|----------|
| `config.json` | 프로젝트별 라우팅, 캘린더 ID, Slack 채널 | ingest, worker |
| `scheduler-config.json` | 일일/주간/월간 보고서 설정 | scheduler |
| `team-members.json` | 팀원 목록 (Slack/Jira/역할 매핑) | ingest, worker, scheduler |
| `groupware-config.json` | 그룹웨어/EKP 부재 신청 규칙 | groupware |
| `announcements.json` | 팀 공지사항 (날짜 범위 기반) | scheduler |
| `google-credentials.json` | Google 서비스 계정 키 (**민감 정보**) | worker, scheduler |
| `rules/DAILY_REPORT_RULES.md` | Claude 일일 보고서 프롬프트 | scheduler |
| `rules/WEEKLY_REPORT_RULES.md` | Claude 주간 보고서 프롬프트 | scheduler |

## Lambda 아키텍처 패턴

### Slack 3초 제한

`view_submission` 등 Slack 인터랙션은 3초 내 HTTP 응답 필수.
- Lambda는 `handleRequest()` 리턴 후에만 HTTP 응답 전송
- 무거운 작업(S3, 외부 API)은 SQS 위임 또는 사전 캐싱

### 정적 캐싱 (Lambda 컨테이너 재사용)

Lambda 컨테이너는 warm 호출 시 재사용되므로 static 필드가 유지됨.
- S3Client, GoogleCalendarClient 등 생성 비용이 큰 객체를 `static volatile`로 캐싱
- 요청마다 생성 시 S3Client ~300ms, API 클라이언트 ~1200ms 소요

### Pre-warm 패턴

`handleCommand()` 시점에 daemon 스레드로 초기화 시작, `handleModalSubmit()` 시점에 사용.
- `setDaemon(true)` — Lambda 응답 차단 방지
- `join(timeout)` — 타임아웃 내 완료 보장

## 테스트

현재 유닛 테스트는 구성되어 있지 않음. 변경사항은 `make build`로 컴파일 검증.

## 주의사항

- `config/google-credentials.json`은 민감 정보 — 내용을 출력하거나 수정하지 말 것
- `config/` 디렉토리의 파일은 S3에 업로드되어 런타임에 사용됨 — 구조 변경 시 관련 모듈 코드도 함께 수정 필요
- Jira Cloud REST API는 POST `/rest/api/3/search/jql` 사용 (GET `/rest/api/3/search`는 HTTP 410 반환)
- Confluence Cloud는 인덱싱 지연 이슈로 3단계 페이지 검색 전략 적용 중
- Lambda Shadow JAR 빌드 시 META-INF 서명 파일 제거 필수 (`mergeServiceFiles`)
- build.sh의 Lambda 함수명은 참고용 — 실제 배포는 Makefile이 정본 (build.sh 내 ingest 함수명 불일치 주의)
