# CLAUDE.md

이 파일은 Claude Code가 프로젝트 작업 시 자동으로 참조하는 컨텍스트 파일입니다.
모듈별 상세 규칙은 `.claude/rules/`에서 해당 파일 작업 시 자동 로딩된다.

## 프로젝트 개요

**automation-platform** — Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반 AWS 서버리스 업무 자동화 플랫폼.

- **Java 17** (Gradle 멀티모듈) + **Python 3.11** (groupware-bot)
- **런타임**: Lambda (Java), Fargate (Python Docker)
- **리전**: ap-northeast-2

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

**의존성**: `common ← clients ← ingest / worker / scheduler / groupware`
- common, clients는 상위 모듈 코드를 참조하지 말 것
- groupware-bot은 독립 Python 프로젝트 (Gradle 미포함)

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

## 빌드/배포

```bash
make build                          # 전체 shadowJar 빌드
make build-ingest                   # 모듈별 빌드 (build-worker, build-scheduler, build-groupware)
make build-bot                      # groupware-bot Docker 빌드
make deploy-all                     # 전체 배포
make deploy-ingest                  # 모듈별 배포 (deploy-worker, deploy-scheduler, deploy-groupware)
make push-bot                       # ECR 푸시
./gradlew :moduleName:compileJava   # 모듈별 컴파일 검증 (빠른 확인)
```

## 코딩 컨벤션

### Java
- **Java 17** 문법을 사용할 것 (record, sealed class, text block, pattern matching 등)
- **Lombok** 사용: `@Slf4j`, `@Getter`, `@Builder`, `@RequiredArgsConstructor`
- **패키지**: `com.riman.automation.<module>.<layer>.<class>`
  - layer: `handler`, `facade`, `service`, `dto`, `payload`, `security`, `util`
  - scheduler 추가: `collect`, `format`, `excel`, `load`, `report`, `tool`
- **예외**: `common` 모듈의 4가지 예외 클래스만 사용할 것 (상세: `.claude/rules/common-clients.md`)
- **로깅**: SLF4J (`@Slf4j`), `log.info/warn/error`
- **JSON**: Jackson (`ObjectMapper`), `jackson-datatype-jsr310`으로 Java Time 지원
- **날짜/시간**: `java.time` API (LocalDate, LocalDateTime, ZonedDateTime), KST 기준
- **Null 처리**: 방어적 null 체크, Optional은 반환타입에만 제한적 사용

### Python (groupware-bot)
- Python 3.11, Playwright (Chromium headless), boto3, 동기 방식 (asyncio 미사용)

### 공통
- 커밋 메시지: 한국어 또는 영어, 간결하게
- 환경변수명: `UPPER_SNAKE_CASE` / S3 설정 키: `kebab-case.json`

## AWS 핵심 서비스

- **Lambda**: ingest, worker, scheduler, groupware 실행
- **API Gateway**: Slack 커맨드, Jira 웹훅 수신 (동기 프록시 통합)
- **SQS**: ingest→worker, worker→groupware 비동기 전달
- **S3**: 설정 파일, Lambda JAR, 스크린샷
- **DynamoDB**: Jira-Calendar 매핑, 일정 매핑, 중복 방지
- **Secrets Manager / KMS**: 토큰, 계정 정보, AES-256-GCM 암호화
- **ECS Fargate / ECR**: groupware-bot Docker 실행
- **EventBridge**: Scheduler Lambda 정기 트리거

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

## 테스트

유닛 테스트 미구성. 변경사항은 `make build`로 컴파일 검증할 것.

## 주의사항

- `config/google-credentials.json`은 **민감 정보** — 내용을 출력하거나 수정하지 말 것
- `config/` 파일 구조 변경 시 관련 모듈 코드도 함께 수정할 것
- Jira Cloud REST API: POST `/rest/api/3/search/jql` 사용할 것 (GET `/rest/api/3/search`는 HTTP 410)
- Confluence Cloud: 인덱싱 지연으로 3단계 페이지 검색 전략 적용 중 — 검색 로직 변경 시 주의할 것
- Lambda Shadow JAR: META-INF 서명 파일 제거 필수 (`mergeServiceFiles`)
- build.sh의 Lambda 함수명은 참고용 — 실제 배포는 **Makefile이 정본**
- Lambda는 `handleRequest()` 리턴 후에만 HTTP 응답을 전송함 — 비동기 처리가 필요하면 SQS 위임 또는 `join(timeout)` 패턴을 사용할 것
- 생성 비용이 큰 클라이언트(S3Client ~300ms, GoogleCalendarClient ~1200ms)는 `static volatile`로 캐싱할 것
