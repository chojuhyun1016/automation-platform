# automation-platform — SPEC (Implementation Specification)

## Context

Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반 AWS 서버리스 업무 자동화 플랫폼.
Java 17 (Gradle 멀티모듈) + Python 3.11 (groupware-bot).
PRD: `automation-platform-prd.md` 참조.

### 현재 상태

- **인프라**: Lambda 4개(ingest, worker, scheduler, groupware) + ECS Fargate 1개(groupware-bot)
- **Slack 커맨드**: /부재등록, /재택근무, /계정관리, /일정등록, /현재티켓 (5개 완료)
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
- [x] `/현재티켓` — 담당 Jira 티켓 현황 조회 (daily/weekly/quarterly)

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
