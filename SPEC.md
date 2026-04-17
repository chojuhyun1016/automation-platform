# automation-platform — SPEC (Implementation Specification)

## Context

Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반 AWS 서버리스 업무 자동화 플랫폼.
Java 17 (Gradle 멀티모듈) + Python 3.11 (groupware-bot).
PRD: `automation-platform-prd.md` 참조.

### 현재 상태

- **인프라**: Lambda 4개(ingest, worker, scheduler, groupware) + ECS Fargate 1개(groupware-bot)
- **Slack 커맨드**: /부재등록, /재택근무, /계정관리, /일정등록, /현재티켓, /점심카드 (6개 완료)
- **Jira 동기화**: CREATE/UPDATE/DELETE → Calendar 자동 반영
- **보고서**: Daily(Slack DM), Weekly/Monthly(Confluence + Excel)
- **그룹웨어**: Playwright 브라우저 자동화 (EKP 부재 신청)
- **테스트**: JUnit 5 + Mockito (common, clients, worker 유닛 테스트), CI 자동 실행

---

## Implemented Features (완료)

### F1. Slack 슬래시 커맨드 (5개)

- [x] `/부재등록` — 부재 캘린더 + 그룹웨어 연동
- [x] `/재택근무` — 재택근무 캘린더 등록/취소
- [x] `/계정관리` — 그룹웨어 계정 KMS 암호화 관리
- [x] `/일정등록` — 일정 캘린더 CRUD + DynamoDB 매핑
- [x] `/현재티켓` — 담당 Jira 티켓 현황 조회 (daily/weekly/monthly/quarterly)

### F2. Jira 웹훅 동기화

- [x] Jira 이슈 CREATE → Calendar 종일 이벤트 생성
- [x] Jira 이슈 UPDATE → Calendar 이벤트 수정 (마감일, 담당자, 상태)
- [x] Jira 이슈 DELETE → Calendar 이벤트 삭제
- [x] DynamoDB 2계층 조회 (매핑 + extendedProperties 폴백)
- [x] startDate 정규화 (null → 오늘, startDate > dueDate → dueDate)
- [x] Slack 알림 (채널 + 담당자 DM)

### F3. EventBridge 보고서

- [x] Daily 보고서 → Slack DM (팀원별)
- [x] Weekly 보고서 → Confluence + Slack + Excel
- [x] Monthly 보고서 → Confluence + Slack + Excel
- [x] AI 요약 (Anthropic Claude, 선택적)
- [x] 6개 Collector (Calendar, Jira, DynamoDB 데이터 수집)
- [x] S3 규칙 파일 기반 AI 프롬프트 (ReportRulesService)

### F4. 그룹웨어 부재 자동화

- [x] Java Lambda 오케스트레이터 → ECS Fargate 호출
- [x] Python Playwright 브라우저 자동화 (Chromium headless)
- [x] KMS 봉투 암호화 (AES-256-GCM)
- [x] cancel은 자동화 불가 → Slack DM 수동 안내

### F5. Lambda 최적화

- [x] 병렬 초기화 (SlackFacade, ExecutorService(5))
- [x] Static volatile 캐싱 (S3Client, CalendarClient)
- [x] Pre-warm 데몬 스레드 (CurrentTicketFacade)
- [x] SQS 위임 + join() 패턴
- [x] DynamoDB Pre-warm 연결 (ScheduleMappingQueryService)
- [x] ConfigService 5분 TTL 캐시

---

## Phase N1: 테스트 기반 구축 + 순수 함수 유닛 테스트 (#1)

- [x] Phase N1 완료 (PR #2)

### 오버뷰

Gradle 테스트 환경 구축 + 외부 의존성 없는 순수 함수부터 유닛 테스트 작성.
이후 모든 새 기능은 TDD(skills/tdd-workflow)로 개발.

common, clients 모듈부터 유닛 테스트를 도입한다. JUnit 5 + Mockito.

### 수정/개선

- [x] `build.gradle` (root) — JUnit 5, Mockito, AssertJ 의존성 추가
- [x] `common/build.gradle` — 루트 subprojects에서 공통 처리
- [x] common/exception — 예외 생성, errorCode 검증 테스트
- [x] common/code — Enum 팩토리 메서드 테스트 (경계값, null 처리)
- [x] common/util — DateTimeUtil KST 변환 테스트
- [x] common/slack — SlackBlockBuilder 체이닝 테스트
- [x] clients/http — BaseHttpClient.requireSuccess() + ApiResponse 테스트

### 검증

- [x] `./gradlew :common:test` 통과
- [x] `./gradlew :clients:test` 통과

---

## Phase N2: worker/ingest 서비스 Mock 테스트 (#3)

- [x] Phase N2 시작

### 전제조건

- [x] Phase N1 완료

### 오버뷰

외부 의존성(S3, DynamoDB, Google Calendar, Slack)을 Mockito로 mock하여 비즈니스 로직 테스트.

### 수정/개선

- [x] CalendarService — processJiraEvent() CREATE/UPDATE/DELETE 분기 테스트
- [x] ConfigService — TTL 캐시 만료/갱신 테스트
- [x] DedupeService — 중복 감지 + prefix 키 테스트
- [x] TeamMemberService — findByAccountId/findBySlackUserId 테스트
- [x] SlackNotificationService — DM/채널 분기 테스트
- [x] AbsenceFacade — 파이프라인 테스트 (날짜 보정, 중복 확인, 캘린더 처리)

### 검증

- [x] `./gradlew :worker:test` 통과 (67 tests)
- [x] `./gradlew :ingest:test` 통과

## Phase N2.5: TDD 프로세스 정착 (#5)

- [x] Phase N2.5 완료

### 전제조건

- [x] Phase N2 완료

### 오버뷰

이후 모든 새 기능/버그 수정에 TDD 적용. resolve-issue 워크플로우에 테스트 검증 단계 강화.

### 수정/개선

- [x] resolve-issue.md 7단계(구현)에 "테스트 먼저 작성" 지시 추가 (TDD 적용 기준 이미 반영됨)
- [x] PostToolUse Hook에 `./gradlew :모듈:test` 자동 실행 추가 (컴파일 + 테스트)
- [x] Stop Hook에 전체 테스트 실행 추가 (`./gradlew test`)

### 검증

- [ ] 새 기능 PR에 테스트 파일 포함 확인

---

## Phase N3: Confluence 페이지 계층 안정화 (#7)

- [x] Phase N3 완료

### 오버뷰

ConfluenceClient 3단계 검색의 인덱싱 지연 대응을 강화하고, 중복 페이지 처리를 개선한다.

### 수정/개선

- [x] ConfluenceClient — retry + 지수 백오프 추가
- [x] 중복 페이지 자동 정리 (자식 없는 중복 삭제)
- [x] 주간/월간 보고서 페이지 생성 실패 시 Slack 알림

### 검증

- [x] Weekly/Monthly 보고서 Confluence 정상 생성

---

## Phase N4: 모니터링 & 알림 강화 (#10)

- [x] Phase N4 완료

### 수정/개선

- [x] Lambda 에러 → Sentry 연동
- [x] SQS DLQ 메시지 → Slack 알림
- [x] Calendar API 쿼타 초과 → 경고 DM
- [x] groupware-bot 실패 → S3 스크린샷 + Slack DM (현재 구현) 검증

---

## Phase N5: 보고서 커스터마이징 (#11)

- [x] Phase N5 완료

### 수정/개선

- [x] 팀원별 보고서 포맷 커스터마이징 (scheduler-config.json)
- [x] 프로젝트별 보고서 분리 (현재 전체 통합)
- [x] 보고서 히스토리 아카이빙 (S3)

---

## Phase N6: scheduler/groupware 테스트 확장

- [x] Phase N6 완료

### 전제조건

- [x] Phase N2 완료

### 오버뷰

N2에서 커버하지 못한 scheduler, groupware 모듈의 유닛 테스트를 추가한다.

### 수정/개선

- [x] scheduler/build.gradle — testImplementation 불필요 (root build.gradle 공통 제공)
- [x] CalendarTicketParser — 이슈키 추출, 담당자 파싱, 상태 감지 테스트
- [x] DailyCalendarTicketCollector — 수집 로직 Mock 테스트
- [x] DailyAbsenceCollector — 2개 캘린더 병합 + 중복 제거 테스트
- [x] ReportRulesService — S3 규칙 파일 로드 + 캐시 테스트
- [x] groupware/build.gradle — testImplementation 불필요 (root build.gradle 공통 제공)
- [x] GroupwareAbsenceFacade — apply/cancel 분기, 결재자 resolve 테스트

### 검증

- [x] `./gradlew :scheduler:test` 통과
- [x] `./gradlew :groupware:test` 통과

---

## Phase N7: CI 자동 테스트 (GitHub Actions)

- [x] Phase N7 완료 (#15)

### 전제조건

- [x] Phase N1 완료 (최소 1개 모듈에 테스트 존재)

### 오버뷰

PR 생성/업데이트 시 GitHub Actions에서 전체 테스트를 자동 실행한다.
테스트 실패 시 PR merge 차단.

### 수정/개선

- [x] `.github/workflows/ci-test.yml` — PR 시 `./gradlew build` 자동 실행
- [x] Java 17 + Gradle 캐시 설정 (setup-java + setup-gradle)
- [x] 테스트 실패 시 PR에 실패 코멘트 자동 생성
- [x] 테스트 리포트 Artifact 업로드 (7일 보관)
- [ ] branch protection rule: 테스트 통과 필수 (GitHub Settings에서 수동 설정)

### 검증

- [x] PR 생성 시 GitHub Actions에서 테스트 실행 확인
- [x] 테스트 실패 PR에 경고 코멘트 자동 생성 확인

---

## Phase N8: /현재티켓 월별(monthly) 조회 기능 추가 (#21)

- [x] Phase N8 완료 (PR #22)

### 오버뷰

`/현재티켓` 커맨드에 "월별" 기간 옵션을 추가한다. 기존 daily/weekly/quarterly 패턴과 동일하게 월말일 기준으로 필터링한다.

### 메타
- **라벨**: feature
- **우선순위**: medium
- **병렬 가능**: 예 (독립 기능, ingest 모듈 내부 변경)

### 전제조건
- 없음

### 수정/개선
- [x] **`ingest/src/main/java/com/riman/automation/ingest/payload/CurrentTicketModalBuilder.java`** — PERIOD_OPTIONS에 `{"monthly", "월별"}` 추가
    - [x] quarterly 앞에 monthly 삽입 (daily → weekly → monthly → quarterly 순서)
- [x] **`ingest/src/main/java/com/riman/automation/ingest/dto/slack/CurrentTicketModalSubmit.java`** — monthly 지원
    - [x] `isMonthly()` 편의 메서드 추가
    - [x] 클래스 Javadoc에 `"monthly"` 옵션 추가
- [x] **`ingest/src/main/java/com/riman/automation/ingest/facade/CurrentTicketFacade.java`** — monthly 필터/표시 로직
    - [x] `sendTicketDm()` switch문에 `case "monthly"` 추가: `dueDate == null || dueDate <= 이번달 말일`
    - [x] `buildPeriodTitle()` switch문에 `case "monthly"` 추가: `"📅 월별 미완료 티켓 조회"`
    - [x] `buildPeriodDetail()` switch문에 `case "monthly"` 추가: `"기준월: *MM/01 ~ MM/말일* 이하 마감"`
- [x] **`.claude/rules/ingest.md`** — period 필터 조건 문서에 monthly 추가
- [x] **`CLAUDE.md`** (루트) — `/현재티켓` 설명에 monthly 추가

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 테스트 통과
- [ ] Slack에서 `/현재티켓` → 드롭다운에 "월별" 옵션 표시 확인
- [ ] "월별" 선택 시 이번달 말일 이하 마감 티켓만 조회되는지 확인

### 리스크
- 없음 (기존 패턴 완전 동일, 신규 의존성 없음)

---

## Phase N9: 주석 리팩토링 - common + clients 모듈 (#23)

- [x] Phase N9 완료 (PR #33)

### 오버뷰
common(18파일)과 clients(8파일) 모듈의 전체 주석을 재작성하고 Google Java Style 포맷팅을 적용한다. 소스 코드 로직은 절대 변경하지 않는다.

### 메타
- **라벨**: refactor
- **우선순위**: medium
- **병렬 가능**: 예 (모든 N9-N13은 주석/포맷만 변경, 전체 병렬 가능)

### 전제조건
- 없음

### 작업 규칙 (N9-N13 공통)
- 소스 코드 로직 절대 변경 금지 (주석, 공백, 들여쓰기만 수정)
- 히스토리성 주석 제거 (날짜, 작성자, 변경 이력 등)
- 주석에 특수문자, 기호, HTML 사용 금지
- 클래스 Javadoc: 핵심 역할 1-2문장
- 주요 public 메서드: 동작, 파라미터, 리턴값, 예외 설명
- private/내부 메서드: 복잡한 로직만 간결하게 설명
- 중요 비즈니스 로직 흐름은 상세하게 기술
- 들여쓰기: 스페이스 2칸 (Google Java Style)
- import 정렬, 빈 줄 정리

### 수정/개선
- [x] **common/auth/** (3파일) — TokenProvider, BasicTokenProvider, EnvTokenProvider 주석 정리
- [x] **common/code/** (7파일) — Enum 클래스 주석 정리 (AbsenceTypeCode, DueDateUrgencyCode, JiraPriorityCode, JiraStatusCode, ReportPeriodCode, ReportWeekCode, WorkStatusCode)
- [x] **common/exception/** (4파일) — 예외 클래스 주석 정리
- [x] **common/model/** (1파일) — GroupwareAccountInfo 주석 정리
- [x] **common/slack/** (1파일) — SlackBlockBuilder 주석 정리 (빌더 메서드 상세 설명)
- [x] **common/util/** (2파일) — DateTimeUtil, SentryInitializer 주석 정리
- [x] **clients/http/** (3파일) — BaseHttpClient, ApiResponse, SharedHttpClient 주석 정리 (HTTP 통신 흐름 상세)
- [x] **clients/anthropic/** (1파일) — AnthropicClient 주석 정리
- [x] **clients/calendar/** (1파일) — GoogleCalendarClient 주석 정리 (캐싱 패턴 상세)
- [x] **clients/confluence/** (1파일) — ConfluenceClient 주석 정리
- [x] **clients/jira/** (1파일) — JiraClient 주석 정리
- [x] **clients/slack/** (1파일) — SlackClient 주석 정리

### 검증
- [x] `./gradlew :common:compileJava` 빌드 성공
- [x] `./gradlew :clients:compileJava` 빌드 성공
- [x] `./gradlew :common:test` 테스트 통과
- [x] `./gradlew :clients:test` 테스트 통과

---

## Phase N10: 주석 리팩토링 - ingest 모듈 (#24)

- [x] Phase N10 완료 (PR #36)

### 오버뷰
ingest 모듈(25파일, 5,458 LOC)의 전체 주석을 재작성하고 Google Java Style 포맷팅을 적용한다. Slack 커맨드 수신부터 SQS 위임까지의 흐름이 주석으로 파악 가능하도록 한다.

### 메타
- **라벨**: refactor
- **우선순위**: medium
- **병렬 가능**: 예

### 전제조건
- 없음

### 수정/개선
- [x] **handler/** (1파일) — IngestHandler Lambda 진입점 주석 (요청 라우팅 흐름 상세)
- [x] **facade/** (5파일) — SlackFacade, CurrentTicketFacade, AccountManageFacade, JiraWebhookFacade, ScheduleManageFacade
    - [x] SlackFacade: 커맨드 라우팅 분기 흐름 상세 기술
    - [x] CurrentTicketFacade: 기간별 필터링 로직 상세 기술
    - [x] ScheduleManageFacade: CRUD 분기 흐름 상세 기술
- [x] **service/** (5파일) — GroupwareCredentialService, PasswordEncryptionService, ScheduleMappingQueryService, SlackApiService, WorkerMessageService
    - [x] PasswordEncryptionService: KMS 암호화 흐름 상세 기술
    - [x] WorkerMessageService: SQS 메시지 위임 패턴 상세 기술
- [x] **payload/** (5파일) — AbsenceModalBuilder, AccountModalBuilder, CurrentTicketModalBuilder, RemoteWorkModalBuilder, ScheduleModalBuilder
- [x] **dto/slack/** (6파일) — SlackCommandRequest, AbsenceModalSubmit, AccountModalSubmit, CurrentTicketModalSubmit, RemoteWorkModalSubmit, ScheduleModalSubmit
- [x] **dto/jira/** (1파일) — JiraWebhookEvent
- [x] **security/** (1파일) — SlackSignatureVerifier (서명 검증 흐름 상세)
- [x] **util/** (1파일) — HttpResponse

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 테스트 통과

---

## Phase N11: 주석 리팩토링 - worker 모듈 (#25)

- [x] Phase N11 완료 (PR #34)

### 오버뷰
worker 모듈(24파일, 4,531 LOC)의 전체 주석을 재작성하고 Google Java Style 포맷팅을 적용한다. SQS 메시지 수신부터 Calendar/DynamoDB 처리까지의 흐름이 주석으로 파악 가능하도록 한다.

### 메타
- **라벨**: refactor
- **우선순위**: medium
- **병렬 가능**: 예

### 전제조건
- 없음

### 수정/개선
- [x] **handler/** (2파일) — WorkerHandler, DlqAlertHandler
    - [x] WorkerHandler: SQS 메시지 디스패치 흐름 상세 기술
    - [x] DlqAlertHandler: DLQ 알림 흐름 상세 기술
- [x] **facade/** (4파일) — AbsenceFacade, JiraIssueFacade, RemoteWorkFacade, ScheduleFacade
    - [x] AbsenceFacade: 부재 등록 전체 오케스트레이션 상세 기술
    - [x] JiraIssueFacade: CREATE/UPDATE/DELETE 분기 흐름 상세 기술
- [x] **service/** (11파일) — AbsenceService, CalendarService, ConfigService, DedupeService, GroupwareMessageService, JiraCalendarMappingService, MonitoringAlertService, RemoteWorkService, ScheduleEventMappingService, SlackNotificationService, TeamMemberService
    - [x] CalendarService: Google Calendar CRUD 패턴 상세 기술
    - [x] JiraCalendarMappingService: DynamoDB 2계층 조회 패턴 상세 기술
    - [x] DedupeService: 중복 방지 로직 상세 기술
    - [x] ConfigService: S3 설정 로딩 및 static 캐싱 패턴 상세 기술
- [x] **payload/** (2파일) — JiraSlackMessageBuilder, SlackTimeHeaderBuilder
- [x] **dto/** (5파일) — JiraWebhookEvent, TeamMember, AbsenceMessage, RemoteWorkMessage, ScheduleMessage

### 검증
- [x] `./gradlew :worker:compileJava` 빌드 성공
- [x] `./gradlew :worker:test` 테스트 통과

---

## Phase N12: 주석 리팩토링 - scheduler 상위 레이어 (#26)

- [x] Phase N12 완료 (PR #35)

### 오버뷰
scheduler 모듈 상위 레이어(18파일)의 주석을 재작성한다. 보고서 파이프라인의 전체 흐름(handler -> facade -> report service -> load service)이 주석으로 파악 가능하도록 한다.

### 메타
- **라벨**: refactor
- **우선순위**: medium
- **병렬 가능**: 예

### 전제조건
- 없음

### 수정/개선
- [x] **handler/** (1파일) — SchedulerHandler Lambda 진입점 (Daily/Weekly/Monthly 분기 흐름 상세)
- [x] **facade/** (3파일) — DailyReportFacade, WeeklyReportFacade, MonthlyReportFacade
    - [x] 각 Facade의 파이프라인 오케스트레이션 흐름 상세 기술
- [x] **service/report/** (3파일) — DailyReportService, WeeklyReportService, MonthlyReportService
    - [x] 수집 -> 포맷 -> 전송 파이프라인 흐름 상세 기술
- [x] **service/load/** (2파일) — ReportRulesService, TeamMemberService
    - [x] ReportRulesService: S3 규칙 파일 로딩 및 AI 프롬프트 구성 상세 기술
- [x] **dto/report/** (3파일) — DailyReportData, WeeklyReportData, MonthlyReportData
- [x] **dto/s3/** (8파일) — AnnouncementItem, ArchiveConfig, DailyReportConfig, MemberReportPreference, MonthlyReportConfig, ProjectGroup, TeamMember, WeeklyReportConfig
- [x] **service/ReportArchiveService.java** (1파일) — Confluence 아카이브 흐름 상세 기술
- [x] **tool/** (1파일) — CalendarStartDateFixer

### 검증
- [x] `./gradlew :scheduler:compileJava` 빌드 성공

---

## Phase N13: 주석 리팩토링 - scheduler 하위 레이어 + groupware (#27)

- [x] Phase N13 완료 (PR #37)

### 오버뷰
scheduler 모듈 하위 레이어(16파일)와 groupware 모듈(4파일)의 주석을 재작성한다. 데이터 수집, 포맷팅, Excel 생성의 세부 로직이 주석으로 파악 가능하도록 한다.

### 메타
- **라벨**: refactor
- **우선순위**: medium
- **병렬 가능**: 예

### 전제조건
- 없음

### 수정/개선
- [x] **scheduler/service/collect/** (6파일) — DailyAbsenceCollector, DailyCalendarTicketCollector, DailyJiraTicketCollector, DailyScheduleCollector, MonthlyCalendarTicketCollector, WeeklyCalendarTicketCollector
    - [x] 각 Collector의 데이터 수집 대상, 필터 조건, 반환 형식 상세 기술
- [x] **scheduler/service/format/** (3파일) — DailyReportFormatter, WeeklyReportFormatter, MonthlyReportFormatter
    - [x] Slack Block Kit 메시지 구성 흐름 상세 기술
- [x] **scheduler/service/excel/** (2파일) — WeeklyExcelGenerator, MonthlyExcelGenerator
    - [x] Excel 시트 구성, 셀 매핑 로직 상세 기술
- [x] **scheduler/service/util/** (1파일) — CalendarTicketParser (파싱 규칙 상세)
- [x] **groupware/handler/** (1파일) — GroupwareHandler
- [x] **groupware/facade/** (1파일) — GroupwareAbsenceFacade (ECS 오케스트레이션 흐름 상세)
- [x] **groupware/service/** (1파일) — EcsTaskService (Fargate 태스크 실행 패턴 상세)
- [x] **groupware/dto/** (1파일) — GroupwareAbsenceMessage

### 검증
- [x] `./gradlew :scheduler:compileJava` 빌드 성공
- [x] `./gradlew :scheduler:test` 테스트 통과
- [x] `./gradlew :groupware:compileJava` 빌드 성공
- [x] `./gradlew :groupware:test` 테스트 통과

---

## Phase N14: 점심카드 — worker 기반 (Calendar 서비스 + Facade) (#38)

- [x] Phase N14 완료 (PR #43)

### 오버뷰
점심카드 비즈니스 로직 기반을 worker 모듈에 구축한다. config에 캘린더 ID를 추가하고, Calendar CRUD + 팀 채널 알림을 처리하는 서비스/Facade를 생성한다.

### 메타
- **라벨**: feature
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N13 완료 (PR #37)

### 수정/개선
- [x] **`config/config.json`** — `lunchCard` 섹션 추가
    - [x] `calendar_id`: 보상코어 개발팀 캘린더 ID
    - [x] `notification_channel_id`: `C09DAQAABS5`
- [x] **`worker/.../service/ConfigService.java`** — 점심카드 설정 접근 메서드 추가
    - [x] `getLunchCardCalendarId()` (fallback: routing.CCE.calendar_id)
    - [x] `getLunchCardNotificationChannelId()`
- [x] **`worker/.../service/LunchCardService.java`** — Calendar CRUD 서비스 (신규)
    - [x] `findLunchCardEvents(calendarId, weekStart, weekEnd)` — 주간/월간 이벤트 조회
    - [x] `findLunchCardEvent(calendarId, date)` — 특정 날짜 이벤트 1건 조회
    - [x] `applyLunchCard(calendarId, name, date)` — 종일 이벤트 생성 (중복 검증)
    - [x] `cancelLunchCard(calendarId, name, date)` — 본인 이벤트만 삭제
    - [x] 이벤트 제목: `점심카드(사용자명)`, transparency=transparent
- [x] **`worker/.../dto/sqs/LunchCardMessage.java`** — SQS 메시지 DTO (신규)
    - [x] 필드: messageType, eventId, receivedAt, action(apply/cancel), slackUserId, name, date
- [x] **`worker/.../facade/LunchCardFacade.java`** — Worker Facade (신규)
    - [x] `handle(String body)` — JSON 파싱 → 이름 resolve → 중복 체크 → LunchCardService → 알림
    - [x] DedupeService 사용 (prefix: `LUNCH#`)
    - [x] 팀 채널(C09DAQAABS5) 알림: "점심카드 사용: {이름} ({날짜})" / "점심카드 취소: {이름} ({날짜})"
- [x] **`worker/.../handler/WorkerHandler.java`** — dispatch에 `lunch_card` 타입 추가
    - [x] `TYPE_LUNCH_CARD = "lunch_card"` 상수 + LunchCardFacade 초기화
- [x] **테스트**
    - [x] `LunchCardServiceTest.java` — apply/cancel 분기, 타인 등록 시 예외
    - [x] `LunchCardFacadeTest.java` — 파이프라인 + 알림 전송 검증

### 검증
- [x] `./gradlew :worker:compileJava` 빌드 성공
- [x] `./gradlew :worker:test` 테스트 통과

---

## Phase N15: 점심카드 — ingest 라우팅 + SQS 전송 + DTO (#39)

- [x] Phase N15 완료 (PR #44)

### 오버뷰
ingest 모듈에 `/점심카드` 커맨드 라우팅, SQS 전송 메서드, 모달 제출 파싱 DTO를 추가한다.

### 메타
- **라벨**: feature
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N14 완료 (PR #43)

### 수정/개선
- [x] **`ingest/.../dto/slack/SlackCommandRequest.java`** — `/점심카드` 커맨드 판별 추가
    - [x] `LUNCH_CARD_COMMAND = "/점심카드"`, `isLunchCardCommand()`
- [x] **`ingest/.../dto/slack/LunchCardModalSubmit.java`** — view_submission 파싱 DTO (신규)
    - [x] `parse(String body)`, private_metadata: `userId|displayName|date`
- [x] **`ingest/.../service/WorkerMessageService.java`** — `sendLunchCard()` 메서드 추가
- [x] **`ingest/.../facade/LunchCardFacade.java`** — ingest Facade (신규, stub)
    - [x] `handleCommand()`, `handleModalSubmit()`, `handleBlockAction()` stub
    - [x] static volatile 캐싱: S3Client, GoogleCalendarClient, TeamMemberMap
- [x] **`ingest/.../facade/SlackFacade.java`** — 라우팅 추가
    - [x] `CALLBACK_LUNCH_CARD = "lunch_card_submit"`, 풀 크기 5→6
    - [x] handleSlashCommand, handleModalSubmit, handleBlockActions 분기 추가

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 테스트 통과

---

## Phase N16: 점심카드 — 모달 UI 구축 (LunchCardModalBuilder + 동적 갱신) (#40)

- [x] Phase N16 완료 (PR #45)

### 오버뷰
점심카드 모달의 Block Kit JSON 빌드를 구현한다. 날짜별 사용 현황 조회, 주간/월간 카운트, 상태별 UI 분기를 처리한다.

### 메타
- **라벨**: feature
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N15 완료 (PR #44)

### 수정/개선
- [x] **`ingest/.../payload/LunchCardModalBuilder.java`** — 모달 JSON 빌더 (신규)
    - [x] `build()` (views.open), `buildUpdate()` (views.update)
    - [x] Datepicker + 주/월 토글(좌우 동시 전환) + 카운트 + 요일별 사용자
    - [x] 상태 분기: 미등록 / 본인 등록 / 타인 등록
    - [x] 사용/취소 라디오 + 신청 버튼 + "타인이 이미 사용" 안내
- [x] **`ingest/.../facade/LunchCardFacade.java`** — handleCommand, handleBlockAction 완성
    - [x] Calendar 조회 + 모달 빌드 + openView/updateView
    - [x] 카운트 헬퍼: countEvents, buildDayOfWeekMap
- [x] **`ingest/.../service/SlackApiService.java`** — `openLunchCardModal()`, `updateLunchCardView()` 추가
- [x] **테스트**
    - [x] `LunchCardModalBuilderTest.java` — JSON 구조, 3가지 상태별 UI 검증

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 테스트 통과

### 리스크
- Slack 체크박스 disabled 제한 → 상태별 다른 블록 렌더링으로 우회
- 콜드스타트 trigger_id 만료 → expired_trigger_id 에러 핸들링 + static volatile 캐싱

---

## Phase N17: 점심카드 — submit 응답 + 결과 팝업 (#41)

- [x] Phase N17 완료 (PR #46)

### 오버뷰
신청/취소 submit 후 결과 팝업(modalResult) 표시를 완성한다.

### 메타
- **라벨**: feature
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N16 완료 (PR #45)

### 수정/개선
- [x] **`ingest/.../facade/LunchCardFacade.java`** — handleModalSubmit 완성
    - [x] SQS 위임 + join() + modalResult 응답
    - [x] 유효성 검증: date/action 빈값 → modalError
- [x] **`ingest/.../dto/slack/LunchCardModalSubmit.java`** — 유효성 검증 강화
- [x] **테스트**
    - [x] `LunchCardFacadeTest(ingest).java` — submit 응답 + SQS 전송 검증

### 검증
- [x] `./gradlew build` 전체 빌드 성공
- [x] `./gradlew test` 전체 테스트 통과

---

## Phase N18: 점심카드 — 문서 갱신 (#42)

- [x] Phase N18 완료 (PR #47)

### 오버뷰
CLAUDE.md, rules, SPEC.md 등 문서를 동기화한다.

### 메타
- **라벨**: docs
- **우선순위**: medium
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N17 완료

### 수정/개선
- [x] **`CLAUDE.md`** (루트) — Slack 커맨드 테이블에 `/점심카드` 추가
- [x] **`ingest/CLAUDE.md`** — LunchCardFacade 추가
- [x] **`worker/CLAUDE.md`** — LunchCardFacade 추가
- [x] **`.claude/rules/ingest.md`** — lunch_card_submit 추가
- [x] **`.claude/rules/worker.md`** — lunch_card 추가

### 검증
- [x] `./gradlew build` 전체 빌드 성공
- [x] 문서 일관성 확인

---

## Phase N19: 점심카드 — 주/월 토글 제거 + 카운트 동시 표시 (#48)

- [x] Phase N19 완료 (PR #50)

### 오버뷰
불필요한 "조회 기간" 주간/월간 라디오 버튼을 제거하고, 날짜 선택 시 주간/월간 카운트를 동시에 표시한다.

### 메타
- **라벨**: enhancement
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N18 완료

### 수정/개선
- [x] **`ingest/src/main/java/.../payload/LunchCardModalBuilder.java`** — 주/월 토글 블록 제거, 카운트 동시 표시
    - [x] `ViewData` record에서 `periodMode`, `dailyCount` 필드 제거
    - [x] 주/월 토글 radio_buttons 블록 (block_lunch_card_period) 삭제
    - [x] 카운트 표시를 "주간 사용: *N*회" + "월간 사용: *N*회" 동시 표시로 변경
- [x] **`ingest/src/main/java/.../facade/LunchCardFacade.java`** — periodMode 관련 로직 정리
    - [x] `ACTION_TOGGLE_ID`, `DEFAULT_PERIOD` 상수 제거
    - [x] `buildViewData()`에서 `periodMode` 파라미터 및 `dailyCount` 제거
    - [x] `extractPeriodMode()` 메서드 삭제
    - [x] `handleBlockAction()`에서 periodMode 변수 제거
- [x] **`ingest/src/main/java/.../facade/SlackFacade.java`** — toggle action 라우팅 제거
    - [x] `"action_lunch_card_toggle"` 조건 제거
- [x] **`ingest/src/test/.../payload/LunchCardModalBuilderTest.java`** — 테스트 업데이트
    - [x] `dataWithStatus()` ViewData 생성 수정
    - [x] `build_hasPeriodToggle()` 테스트 삭제
    - [x] `build_hasCountDisplay()` 주간+월간 둘 다 검증으로 수정
- [x] **문서 업데이트** — `ingest/CLAUDE.md`, `.claude/rules/ingest.md`

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과

---

## Phase N20: 점심카드 — 이번주 사용 현황 이름 미표시 버그 수정 (#49)

- [x] Phase N20 완료 (PR #51)

### 오버뷰
"이번주 사용 현황" 요일별 사용자 목록에서 이름이 표시되지 않는 버그를 수정한다.

### 메타
- **라벨**: bug
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N19 완료 (PR #51)

### 수정/개선
- [x] **`ingest/src/main/java/.../facade/LunchCardFacade.java`** — 이름 미표시 버그 수정
    - [x] `extractEventDate()` 접근 제한자 `private static` → `static` (package-private) 변경
    - [x] `buildDayOfWeekMap()`/`buildViewData()`에 디버그 로그 추가
    - [x] Google Calendar all-day event 날짜 파싱 로직 점검 및 수정
- [x] **`ingest/src/test/.../facade/LunchCardFacadeLogicTest.java`** — 테스트 보강
    - [x] `ExtractEventDate` 테스트 클래스 추가 (all-day, dateTime, null 케이스)
    - [x] `BuildDayOfWeekMap` 테스트 보강

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과
- [x] 실제 Slack `/점심카드` 실행 시 이름 정상 표시

---

## Phase N21: 점심카드 — UI 텍스트 변경 + 선택 요일 백틱 하이라이트 (#52)

- [x] Phase N21 완료 (PR #55)

### 오버뷰
"이번 주 사용 현황" → "이번 주 사용자 현황" 텍스트 변경, 선택 날짜 요일의 사용자 이름을 백틱(`` ` ``)으로 하이라이트하여 Slack에서 다른 색상으로 표현한다.

### 메타
- **라벨**: enhancement
- **우선순위**: medium
- **병렬 가능**: 예

### 전제조건
- 없음

### 수정/개선
- [x] **`ingest/src/main/java/.../payload/LunchCardModalBuilder.java`**
    - [x] 라인 121: `"📋 *이번 주 사용 현황*"` → `"📋 *이번 주 사용자 현황*"`
    - [x] `buildBlocks()` 요일별 렌더링 루프(124-133)에서 selectedDate의 요일 판별
    - [x] 선택 요일의 사용자 이름을 `` `이름` `` 으로 감싸기 (Slack mrkdwn 코드 스타일 = 빨간 배경)
    - [x] 요일 판별 헬퍼: `selectedDayLabel(String selectedDate)` — `LocalDate.parse().getDayOfWeek()` → "월"~"금" 매핑
- [x] **`ingest/src/test/java/.../payload/LunchCardModalBuilderTest.java`**
    - [x] "이번 주 사용자 현황" 텍스트 검증
    - [x] 선택 요일 사용자 백틱 하이라이트 테스트 추가

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과

---

## Phase N22: 점심카드 — 버튼 상태 로직 개선 (#53)

- [x] Phase N22 완료 (PR #56)

### 오버뷰
현재 라디오 버튼(사용/취소 모두 선택 가능)을 상태별 단일 체크박스로 변경하여 요구사항의 활성/비활성 조건을 정확히 반영한다.

### 메타
- **라벨**: enhancement
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N21 완료 (PR #55)(LunchCardModalBuilder 수정 충돌 방지)

### 상태별 UI 요구사항
| 상태 | 사용 버튼 | 취소 버튼 | 신청 버튼 | 안내 문구 |
|------|-----------|-----------|-----------|-----------|
| UNREGISTERED (사용자 없음) | 활성 (자동선택) | 비활성 | 활성 | — |
| SELF_REGISTERED (본인) | 비활성 | 활성 (자동선택) | 활성 | — |
| OTHER_REGISTERED (타인) | 비활성 | 비활성 | 비활성 | `` `이름` 님이 이미 사용 중 `` |

### 수정/개선
- [x] **`ingest/src/main/java/.../payload/LunchCardModalBuilder.java`**
    - [x] `addActionBlock()` 메서드를 2개로 분리:
        - `addApplyBlock(blocks)`: UNREGISTERED — 체크박스 1개 (value="apply", text="사용"), `initial_options`로 자동선택
        - `addCancelBlock(blocks)`: SELF_REGISTERED — 체크박스 1개 (value="cancel", text="자동 취소"), `initial_options`로 자동선택
    - [x] Slack Block Kit 타입: `radio_buttons` → `checkboxes` (1개 옵션만)
    - [x] block_id=`block_lunch_card_action`, action_id=`action_lunch_card_action` 유지 (submit 파싱 호환)
    - [x] switch 분기(138-142) 변경
    - [x] `addOtherRegisteredNotice()`: 안내 문구에 백틱 적용 — `` ⚠️ `이름` 님이 이미 사용 중입니다 ``
- [x] **`ingest/src/main/java/.../dto/slack/LunchCardModalSubmit.java`**
    - [x] `radio_buttons`의 `selected_option.value` → `checkboxes`의 `selected_options[0].value` 파싱 변경
    - [x] 체크박스 해제 시 `selected_options` 빈 배열 → 유효성 검증 처리
- [x] **`ingest/src/test/java/.../payload/LunchCardModalBuilderTest.java`**
    - [x] UNREGISTERED: checkboxes type + initial_options "apply" + submit 존재
    - [x] SELF_REGISTERED: checkboxes type + initial_options "cancel" + submit 존재
    - [x] OTHER_REGISTERED: 백틱 안내 문구 + submit 없음
- [x] **`ingest/src/test/java/.../dto/slack/LunchCardModalSubmitTest.java`**
    - [x] checkboxes 파싱 테스트 (selected_options 배열)

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과

### 주의사항
- `LunchCardModalSubmit` 파싱이 `selected_option` (단수) → `selected_options` (복수, 배열)로 바뀜
- block_id, action_id는 기존 값 유지해야 submit 호환

---

## Phase N23: 점심카드 — 팀 채널 알림 검증 + 문서 갱신 (#54)

- [x] Phase N23 완료 (PR #57)

### 오버뷰
worker 모듈의 `LunchCardNotificationService`가 신청/취소 시 팀 채널에 알림을 전송하는지 검증하고, 테스트를 보강한다. Phase N21~N22 변경사항을 문서에 반영한다.

### 메타
- **라벨**: chore
- **우선순위**: low
- **병렬 가능**: 예 (worker 모듈 독립)

### 전제조건
- 없음 (worker 모듈은 독립)

### 수정/개선
- [x] **`worker/src/main/java/.../service/LunchCardNotificationService.java`**
    - [x] 동작 확인 (SLACK_BOT_TOKEN + NOTIFICATION_CHANNEL_ID 환경변수 필수)
    - [x] 봇 이름은 Slack App 설정에서 "C.C.E - Team Bot"으로 관리 (코드 변경 불필요)
- [x] **`worker/src/test/java/.../service/LunchCardNotificationServiceTest.java`** (신규)
    - [x] apply 시 SlackClient.postMessage() 호출 검증
    - [x] cancel 시 메시지 포맷 검증
    - [x] disabled 상태 (botToken 미설정) 시 무동작 검증

### 검증
- [x] `./gradlew :worker:compileJava` 빌드 성공
- [x] `./gradlew :worker:test` 전체 테스트 통과

---

## Phase N24: 점심카드 — Calendar 조회 버그 수정 (카운트/사용자/상태 판별 전면 수정) (#58)

- [x] Phase N24 완료 (PR #59)

### 오버뷰
Google Calendar API `setQ()` 검색이 이벤트를 누락하여 주간/월간 카운트=0, 사용자 현황 미표시, 상태 판별 실패(항상 UNREGISTERED) 버그를 수정한다. `searchQuery=null`로 전체 이벤트 fetch 후 Java에서 summary 기반 필터링하는 패턴을 적용한다.

### 메타
- **라벨**: bug
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N21~N23 완료

### 근본 원인
- `.claude/rules/ingest.md`에 문서화된 알려진 이슈: "Google Calendar API `searchQuery`는 결과 누락 가능 → 전체 이벤트 fetch 후 Java에서 필터링할 것"
- `CurrentTicketFacade`에서는 이 패턴을 적용했으나, `LunchCardFacade`에서는 미적용
- `queryWeekEvents()` / `queryMonthEvents()`가 `SEARCH_QUERY = "점심카드"`로 `setQ()` 호출 → Google Calendar API가 이벤트 누락 → 빈 리스트 반환
- 빈 리스트 → `weeklyCount=0`, `monthlyCount=0`, `dayOfWeekMap` 비어있음, `status=UNREGISTERED`

### 수정/개선
- [x] **`ingest/src/main/java/.../facade/LunchCardFacade.java`**
    - [x] `queryWeekEvents()` (라인 300): `SEARCH_QUERY` → `null`로 변경 (전체 이벤트 fetch)
    - [x] `queryMonthEvents()` (라인 308): `SEARCH_QUERY` → `null`로 변경
    - [x] 전체 이벤트 fetch 후 Java에서 summary가 "점심카드"로 시작하는 이벤트만 필터링하는 헬퍼 추가
    - [x] `buildViewData()` 내 디버그 로그를 `info`로 상향 (운영 디버깅용, 1회성)
- [x] **`ingest/src/test/java/.../facade/LunchCardFacadeLogicTest.java`**
    - [x] summary 필터링 헬퍼 테스트 추가 ("점심카드(홍길동)" 포함, "회의" 미포함)

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과
- [ ] 배포 후 `/점심카드` 실행 시:
    - [ ] 주간/월간 카운트가 정확히 표시
    - [ ] 이번 주 사용자 현황에 이름 표시
    - [ ] 본인 등록 시 SELF_REGISTERED (취소 체크박스)
    - [ ] 타인 등록 시 OTHER_REGISTERED (안내 문구 + 신청 버튼 없음)

---

## Phase N25: 점심카드 — 체크박스 제거 + config.json 기반 설정 + UI 개선 (#60)

- [x] Phase N25 완료 (PR #61)

### 오버뷰
SELF_REGISTERED 체크박스 해제 후 신청 가능한 버그 수정, 알림 미전송 버그 수정, UI 개선을 한 Phase로 처리한다. checkboxes 제거 → action을 private_metadata 인코딩, ingest의 LUNCH_CARD_CALENDAR_ID 환경변수 → config.json 기반으로 변경, worker 알림의 SLACK_BOT_TOKEN → Secrets Manager(config.json routing의 slack_bot_token_secret) 기반으로 변경.

### 메타
- **라벨**: bug
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N24 완료

### 수정/개선

#### A. 체크박스 제거 + action 서버 결정 (ingest)
- [x] **`ingest/src/main/java/.../payload/LunchCardModalBuilder.java`**
    - [x] `buildView()`: private_metadata에 action 인코딩 (`userId|userName|apply` 또는 `userId|userName|cancel`)
    - [x] `addApplyBlock()` / `addCancelBlock()` → 텍스트 섹션으로 교체 (checkboxes 제거)
        - UNREGISTERED: `"✅ 사용 신청이 적용됩니다"`
        - SELF_REGISTERED: `"❌ 취소가 적용됩니다"`
    - [x] OTHER_REGISTERED: submit 버튼 없음 유지 (기존대로)
    - [x] 백틱 하이라이트 → bold 처리 (당일 사용자 `*이름*`)
    - [x] 카운트 포맷 심플화 (`📊 주간 사용: *N*회` → 간결한 표기)
- [x] **`ingest/src/main/java/.../dto/slack/LunchCardModalSubmit.java`**
    - [x] action 파싱: checkboxes `selected_options` → private_metadata 세 번째 필드
    - [x] checkboxes 관련 파싱 코드 제거

#### B. ingest Calendar ID — config.json 기반 (환경변수 제거)
- [x] **`ingest/src/main/java/.../facade/LunchCardFacade.java`**
    - [x] `LUNCH_CARD_CALENDAR_ID` 환경변수 → S3 config.json의 `lunchCard.calendar_id` 로드
    - [x] 기존 `loadTeamMemberMap()` 패턴 참고하여 config.json 로드 + 캐싱
    - [x] `handleBlockAction()`: 3-segment private_metadata 파싱 호환

#### C. worker 알림 — Secrets Manager 기반 Bot 토큰 (환경변수 제거)
- [x] **`worker/src/main/java/.../service/LunchCardNotificationService.java`**
    - [x] `SLACK_BOT_TOKEN` 환경변수 → Secrets Manager 조회로 변경
    - [x] config.json routing 섹션의 `slack_bot_token_secret` 사용 (기존 `SlackNotificationService.getBotToken()` 패턴 참고)
    - [x] 생성자에 `secretName` 파라미터 추가 (또는 `ConfigService`에서 조회)
- [x] **`worker/src/main/java/.../handler/WorkerHandler.java`**
    - [x] LunchCardNotificationService 생성 시 `slack_bot_token_secret` 전달
    - [x] ConfigService 또는 config.json routing에서 CCE의 `slack_bot_token_secret` 조회

#### D. 테스트
- [x] **`ingest/src/test/java/.../payload/LunchCardModalBuilderTest.java`**
    - [x] checkboxes → 텍스트 섹션 검증으로 변경
    - [x] private_metadata 3-segment 포맷 검증
- [x] **`ingest/src/test/java/.../dto/slack/LunchCardModalSubmitTest.java`**
    - [x] private_metadata 기반 action 파싱 테스트

### 참고: config.json 관련 기존 코드
- `config/config.json` (라인 77-80): lunchCard.calendar_id, lunchCard.notification_channel_id
- `config/config.json` routing.CCE: slack_bot_token_secret = "automation-slack-bot-token"
- `worker/.../service/ConfigService.java` (라인 130-159): getLunchCardCalendarId(), getLunchCardNotificationChannelId()
- `worker/.../service/SlackNotificationService.java` (라인 225-246): getBotToken() — Secrets Manager 조회 + 5분 캐시 패턴

### 검증
- [x] `./gradlew :ingest:compileJava` 빌드 성공
- [x] `./gradlew :ingest:test` 전체 테스트 통과
- [x] `./gradlew :worker:compileJava` 빌드 성공
- [x] `./gradlew :worker:test` 전체 테스트 통과
- [ ] 배포 후 UNREGISTERED: 신청 텍스트 + 신청 버튼 → apply
- [ ] 배포 후 SELF_REGISTERED: 취소 텍스트 + 신청 버튼 → cancel (조작 불가)
- [ ] 배포 후 OTHER_REGISTERED: 안내 문구 + 닫기만
- [ ] 배포 후 팀 채널 알림 전송 확인 (CCE - Team Bot)

### 주의사항
- private_metadata 포맷 변경 시 `handleBlockAction()`의 2-segment 파싱도 호환 유지
- ingest에서 config.json 로드 시 기존 `loadTeamMemberMap()` 동일 S3Client 캐싱 사용
- `LUNCH_CARD_CALENDAR_ID` 환경변수는 제거 가능 (config.json으로 완전 대체)

---

## Phase N26: 점심카드 — worker Calendar searchQuery 버그 수정 + 날짜 로직 전면 점검 (#62)

- [x] Phase N26 완료

### 오버뷰
worker `LunchCardService`가 여전히 `q="점심카드"` searchQuery를 사용하여 Calendar 조회 결과가 불안정. ingest Phase N24에서 수정된 동일 패턴(null + Java 필터링)을 worker에도 적용하고, 날짜 필터링/매칭 로직을 전면 점검한다.

### 메타
- **라벨**: bug
- **우선순위**: high
- **병렬 가능**: 아니오

### 전제조건
- [x] Phase N25 완료

### 수정/개선

#### A. worker searchQuery 제거 (핵심)
- [x] **`worker/src/main/java/.../service/LunchCardService.java`**
    - [x] `findLunchCardEvents()`: `SEARCH_QUERY` → `null` + Java 필터링 (`summary.startsWith("점심카드")`)
    - [x] `findLunchCardEvent()`: 위 수정에 맞춰 조정
    - [x] `applyLunchCard()` / `cancelLunchCard()` 동작 확인
- [x] **`worker/src/main/java/.../service/CalendarService.java`** — 변경 없음 확인 (listCalendarEvents는 그대로)

#### B. 날짜 로직 점검 (ingest)
- [x] **`ingest/src/main/java/.../facade/LunchCardFacade.java`**
    - [x] `queryWeekEvents()`: 주간 범위 (월~토 00:00) 정확성 확인
    - [x] `queryMonthEvents()`: 월간 범위 정확성 확인
    - [x] `filterEventsByDate()`: all-day 이벤트 날짜 매칭 확인
    - [x] `extractEventDate()`: all-day vs dateTime 분기 정확성 확인
    - [x] `determineStatus()`: UNREGISTERED/SELF_REGISTERED/OTHER_REGISTERED 판별 정확성 확인
    - [x] `buildDayOfWeekMap()`: 요일별 사용자 매핑 정확성 확인

#### C. 테스트
- [x] **`worker/src/test/java/.../service/LunchCardServiceTest.java`** (신규 또는 기존 보강)
    - [x] searchQuery=null + Java 필터링 동작 검증
    - [x] applyLunchCard 멱등 체크 검증
    - [x] cancelLunchCard 이벤트 찾기/삭제 검증
- [x] **`ingest/src/test/java/.../facade/LunchCardFacadeTest.java`**
    - [x] 날짜 필터링 edge case 검증 (all-day 이벤트)

### 검증
- [x] `./gradlew :worker:compileJava` 빌드 성공
- [x] `./gradlew :worker:test` 전체 테스트 통과
- [x] `./gradlew :ingest:test` 전체 테스트 통과
- [ ] 배포 후 신청 → 재조회 시 즉시 표시 확인
- [ ] 배포 후 취소 → 재조회 시 즉시 반영 확인
- [ ] 배포 후 다른 날짜 이벤트 영향 없음 확인

### 리스크
- Google Calendar API `q` 파라미터 제거 시 전체 이벤트를 fetch하므로 이벤트 수가 많으면 응답 느려질 수 있음 (현재 80건+235건 수준이므로 문제없음)

---

## Phase N27: 점심카드 — UI 안내 문구 제거 + 기존 요구사항 재점검 (#63)

- [ ] Phase N27 완료

### 오버뷰
사용자 요청에 따라 "✅ 사용 신청이 적용됩니다" / "❌ 취소가 적용됩니다" 안내 문구를 제거하고, 기존 점심카드 요구사항 전체를 재점검하여 미반영 항목을 수정한다.

### 메타
- **라벨**: enhancement
- **우선순위**: medium
- **병렬 가능**: Phase N26과 병렬 가능 (수정 파일 겹치지 않음)

### 전제조건
- [ ] 없음 (Phase N26과 병렬 가능)

### 수정/개선

#### A. UI 안내 문구 제거
- [ ] **`ingest/src/main/java/.../payload/LunchCardModalBuilder.java`**
    - [ ] `addApplyBlock()`: "✅ 사용 신청이 적용됩니다" 텍스트 제거 (빈 블록 또는 메서드 자체 제거)
    - [ ] `addCancelBlock()`: "❌ 취소가 적용됩니다" 텍스트 제거
    - [ ] 상태별 submit 버튼 라벨로 충분히 구분: UNREGISTERED → "신청", SELF_REGISTERED → "취소"
- [ ] **submit 버튼 텍스트 변경 검토**
    - [ ] UNREGISTERED: submit 버튼 "신청"
    - [ ] SELF_REGISTERED: submit 버튼 "취소" (현재는 둘 다 "신청"으로 되어 있음 — 수정 필요)

#### B. 기존 요구사항 재점검
- [ ] **카운트 표시 재확인**: 주간/월간 카운트가 해당 사용자가 아닌 전체 사용을 의미하는지 확인
- [ ] **요일별 사용자 현황**: 선택 날짜의 요일에 bold 처리가 정상 동작하는지 확인
- [ ] **OTHER_REGISTERED 상태**: 타인 등록 시 안내 + submit 버튼 미표시 정상 동작 확인
- [ ] **팀 채널 알림**: worker에서 신청/취소 시 알림 전송 정상 동작 확인

#### C. 테스트
- [ ] **`ingest/src/test/java/.../payload/LunchCardModalBuilderTest.java`**
    - [ ] 안내 문구 제거 검증
    - [ ] submit 버튼 텍스트 검증 (신청/취소 분리)

### 검증
- [ ] `./gradlew :ingest:compileJava` 빌드 성공
- [ ] `./gradlew :ingest:test` 전체 테스트 통과
- [ ] 배포 후 UNREGISTERED: submit "신청" + 안내 문구 없음
- [ ] 배포 후 SELF_REGISTERED: submit "취소" + 안내 문구 없음
- [ ] 배포 후 OTHER_REGISTERED: submit 없음 + 타인 안내만

---

## 실행 가이드

Phase 작업을 시작하려면:
1. `/create-issue Phase N1` 실행 → GitHub 이슈 생성 + 아래 표에 자동 역기록
2. 표에서 실행 커맨드 복사
3. 터미널에서 실행

| Phase | 이슈 | 타입 | 실행 커맨드 |
|-------|------|------|-----------|
| N1 | #1 | feat | `source scripts/create-worktree.sh feat 1 unit-test-setup` |
| N2 | #3 | feat | `source scripts/create-worktree.sh feat 3 worker-ingest-mock-test` |
| N2.5 | #5 | chore | `source scripts/create-worktree.sh chore 5 tdd-process-setup` |
| N3 | #7 | fix | `source scripts/create-worktree.sh fix 7 confluence-hierarchy-stabilize` |
| N4 | #10 | feat | `source scripts/create-worktree.sh feat 10 monitoring-alert` |
| N5 | #11 | feat | `source scripts/create-worktree.sh feat 11 report-customization` |
| N6 | #14 | feat | `source scripts/create-worktree.sh feat 14 scheduler-groupware-test` |
| N7 | #15 | chore | `source scripts/create-worktree.sh chore 15 ci-test-enhancement` |
| N8 | #21 | feat | `source scripts/create-worktree.sh feat 21 current-ticket-monthly` |
| N9 | #23 | refactor | `source scripts/create-worktree.sh refactor 23 comment-common-clients` |
| N10 | #24 | refactor | `source scripts/create-worktree.sh refactor 24 comment-ingest` |
| N11 | #25 | refactor | `source scripts/create-worktree.sh refactor 25 comment-worker` |
| N12 | #26 | refactor | `source scripts/create-worktree.sh refactor 26 comment-scheduler-upper` |
| N13 | #27 | refactor | `source scripts/create-worktree.sh refactor 27 comment-scheduler-lower-groupware` |
| N14 | #38 | feat | `source scripts/create-worktree.sh feat 38 lunch-card-worker` |
| N15 | #39 | feat | `source scripts/create-worktree.sh feat 39 lunch-card-ingest-routing` |
| N16 | #40 | feat | `source scripts/create-worktree.sh feat 40 lunch-card-modal-ui` |
| N17 | #41 | feat | `source scripts/create-worktree.sh feat 41 lunch-card-submit` |
| N18 | #42 | docs | `source scripts/create-worktree.sh docs 42 lunch-card-docs` |
| N19 | #48 | feat | `source scripts/create-worktree.sh feat 48 lunch-card-toggle-remove` |
| N20 | #49 | fix | `source scripts/create-worktree.sh fix 49 lunch-card-name-display` |
| N21 | #52 | feat | `source scripts/create-worktree.sh feat 52 lunch-card-ui-highlight` |
| N22 | #53 | feat | `source scripts/create-worktree.sh feat 53 lunch-card-button-state` |
| N23 | #54 | chore | `source scripts/create-worktree.sh chore 54 lunch-card-notification-test` |
| N24 | #58 | fix | `source scripts/create-worktree.sh fix 58 lunch-card-calendar-query-fix` |
| N25 | #60 | fix | `source scripts/create-worktree.sh fix 60 lunch-card-checkbox-config-ui` |
| N26 | #62 | fix | `source scripts/create-worktree.sh fix 62 lunch-card-worker-searchquery` |
| N27 | #63 | feat | `source scripts/create-worktree.sh feat 63 lunch-card-ui-simplify` |

> `/create-issue Phase N1` 실행 시 이슈 번호와 실행 커맨드가 이 표에 자동 기록됩니다.
> 예: `| N1 | #12 | feat | source scripts/create-worktree.sh feat 12 unit-test-setup |`

---

## Bugfix Log

### BF-1: Slack 재시도 감지 강화

- [x] 수정 완료
- **원인**: X-Slack-Retry-Num만 확인하면 다른 Retry-Reason도 무시됨
- **수정**: `X-Slack-Retry-Num` + `X-Slack-Retry-Reason == "http_timeout"` **둘 다** 확인

### BF-2: startDate 정규화

- [x] 수정 완료
- **원인**: startDate null인 이벤트가 Calendar에서 표시 안 됨
- **수정**: null → 오늘, startDate > dueDate → dueDate 자동 보정
- **1회용 도구**: `CalendarStartDateFixer`로 기존 데이터 일괄 보정 완료

### BF-3: DynamoDB 매핑 미등록 (레거시)

- [x] 수정 완료
- **원인**: DynamoDB 도입 전 생성된 Calendar 이벤트는 매핑이 없음
- **수정**: extendedProperties 폴백 검색 → 성공 시 DynamoDB 자동 등록

---

**문서 끝**
