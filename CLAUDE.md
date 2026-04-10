# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- **PRD**: `automation-platform-prd.md` — 제품 요구사항, 아키텍처, 보안, 데이터 모델
- **SPEC**: `SPEC.md` — 구현 상태, Phase별 개선 계획, Bugfix 로그
- **모듈별 CLAUDE.md**: 각 모듈 디렉토리에 `CLAUDE.md`가 있다 (패키지 구조, 핵심 패턴, API)
- **워크플로우**: `WORKFLOW.md` — 개발 워크플로우 (feature-breakdown → create-issue → resolve-issue)
- **상세 규칙**: `.claude/rules/`에서 해당 파일 작업 시 자동 로딩
- **스킬**: `.claude/skills/` — 6개 절차 가이드 (add-slack-command, add-scheduler-report, debug-lambda-timeout, add-dynamodb-table, add-environment-variable, add-api-client)

## 프로젝트 개요

**automation-platform** — Slack 슬래시 커맨드, Jira 웹훅, EventBridge 스케줄 기반 AWS 서버리스 업무 자동화 플랫폼.

- **Java 17** (Gradle 멀티모듈) + **Python 3.11** (groupware-bot)
- **런타임**: Lambda (Java), Fargate (Python Docker)
- **리전**: ap-northeast-2

## 모듈 구조

각 모듈 디렉토리에 `CLAUDE.md`가 있어 상세한 패키지 구조, 클래스 목록, 핵심 패턴을 설명한다.

```
automation-platform/
├── common/          공통 라이브러리 (예외, Enum, 유틸리티, SlackBlockBuilder)    → common/CLAUDE.md
├── clients/         외부 API 클라이언트 (Jira, Slack, Calendar, Confluence)      → clients/CLAUDE.md
├── ingest/          Lambda 진입점 (Slack 커맨드, Jira 웹훅 수신)                → ingest/CLAUDE.md
├── worker/          SQS 소비자 (Jira-Calendar 동기화, 재택/부재/일정 처리)      → worker/CLAUDE.md
├── scheduler/       EventBridge 스케줄러 (일일/주간/월간 보고서)                → scheduler/CLAUDE.md
├── groupware/       Lambda 오케스트레이터 (그룹웨어 부재 신청)                  → groupware/CLAUDE.md
├── groupware-bot/   Python Playwright (브라우저 자동화, Gradle 미포함)           → groupware-bot/CLAUDE.md
└── config/          S3 업로드용 런타임 설정 파일                                → config/CLAUDE.md
```

**의존성**: `common ← clients ← ingest / worker / scheduler / groupware`
- common, clients는 상위 모듈 코드를 참조하지 말 것
- groupware-bot은 독립 Python 프로젝트 (Gradle 미포함)

## 요청 흐름 아키텍처

```
Slack 커맨드 → API Gateway → ingest Lambda (SlackFacade) → SQS → worker Lambda (Facade별 처리)
                                                             └→ groupware SQS → groupware Lambda → Fargate (groupware-bot)

Jira 웹훅   → API Gateway → ingest Lambda (JiraWebhookFacade) → SQS → worker Lambda (JiraIssueFacade)

EventBridge → scheduler Lambda → Slack DM / Confluence
```

- ingest는 3초 내 Slack 응답 후 SQS로 위임, worker가 실제 비즈니스 로직 수행
- scheduler는 EventBridge cron으로 독립 실행

## Slack 슬래시 커맨드

| 커맨드 | 모듈 | Facade | 기능 |
|--------|------|--------|------|
| `/부재등록` | ingest → worker | AbsenceFacade | 부재 캘린더 등록 + 그룹웨어 연동 |
| `/재택근무` | ingest → worker | RemoteWorkFacade | 재택근무 캘린더 등록 |
| `/계정관리` | ingest | AccountManageFacade | 그룹웨어 계정 암호화 관리 |
| `/일정등록` | ingest → worker | ScheduleManageFacade | 일정 캘린더 CRUD + DynamoDB 매핑 |
| `/현재티켓` | ingest | CurrentTicketFacade | 담당 Jira 티켓 현황 조회 |

## 빌드/배포

```bash
make build                          # 전체 shadowJar 빌드
make build-ingest                   # 모듈별 빌드 (build-worker, build-scheduler, build-groupware)
make build-bot                      # groupware-bot Docker 빌드
make clean                          # 빌드 아티팩트 정리
make deploy-all                     # 전체 배포
make deploy-ingest                  # 모듈별 배포 (deploy-worker, deploy-scheduler, deploy-groupware)
make push-bot                       # ECR 푸시
./gradlew :moduleName:compileJava   # 모듈별 컴파일 검증 (빠른 확인)
```

## 코딩 컨벤션

### Java
- **Java 17** 문법을 사용할 것 (record, sealed class, text block, pattern matching 등)
- **패키지**: `com.riman.automation.<module>.<layer>.<class>`
  - layer: `handler`, `facade`, `service`, `dto`, `payload`, `security`, `util`
  - scheduler 추가: `collect`, `format`, `excel`, `load`, `report`, `tool`
- **예외**: `common` 모듈의 4가지 예외 클래스만 사용할 것 (상세: `.claude/rules/common-clients.md`)
- **변수명**: camelCase로 작성할 것
- **Null 처리**: 방어적 null 체크, Optional은 반환타입에만 제한적 사용

### Python (groupware-bot)
- Python 3.11, Playwright (Chromium headless), boto3, 동기 방식 (asyncio 미사용)

### 공통
- 커밋 메시지: 한국어 또는 영어, 간결하게
- 환경변수명: `UPPER_SNAKE_CASE` / S3 설정 키: `kebab-case.json`

## Lambda 핵심 제약사항

- **Slack 응답은 항상 HTTP 200 반환** — 500은 Slack 재시도 루프 + `{reason}` 미치환 버그 유발
- **Slack 3초 제한**: `view_submission` 등 인터랙션은 3초 내 응답 필수 → 무거운 작업은 SQS 위임
- **`handleRequest()` 리턴 후에만 HTTP 응답 전송** — Thread.join() 없이 리턴하면 스레드가 freeze됨
- **Static volatile 캐싱**: 생성 비용 300ms 이상인 객체 (S3Client, GoogleCalendarClient 등)는 `static volatile`로 캐싱
- 상세 패턴은 `.claude/rules/lambda-patterns.md` 참조

## AWS 핵심 서비스

Lambda, API Gateway, SQS, S3, DynamoDB, Secrets Manager/KMS, ECS Fargate/ECR, EventBridge.
SQS/DynamoDB 스키마 상세는 `.claude/rules/worker.md` 참조.

## 설정 파일 (config/)

S3에 업로드되어 런타임에 사용됨. 상세 구조는 `config/README.md` 참조.

## .claude/rules/ 자동 로딩 규칙

| 규칙 파일 | 적용 대상 | 주요 내용 |
|-----------|----------|----------|
| `common-clients.md` | common/**, clients/** | 예외 계층, Enum, SlackBlockBuilder, TokenProvider, API 클라이언트 |
| `ingest.md` | ingest/** | SlackFacade 라우팅, 병렬 초기화, SQS 위임, ScheduleMappingQuery |
| `worker.md` | worker/** | 메시지 디스패치, CalendarService, DynamoDB, SlackNotification |
| `scheduler.md` | scheduler/** | 보고서 파이프라인, Collector 6개, Confluence 계층 |
| `groupware.md` | groupware/**, groupware-bot/** | ECS 오케스트레이션, KMS 봉투 암호화, 자격증명 보안 |
| `lambda-patterns.md` | handler/**, facade/** | 3초 제한, pre-warm, static volatile 캐싱 |
| `calendar-model.md` | Calendar 관련 코드 | Google Calendar 이벤트 모델, extendedProperties |
| `env-vars.md` | 전체 Lambda 모듈 | 모듈별 환경변수 참조 (필수/선택 분류) |
| `agents.md` | — | 에이전트 사용 가이드 (전역 ECC 에이전트 참조) |

## 테스트

유닛 테스트 미구성. 변경사항은 `make build`로 컴파일 검증할 것.

## 문서 동기화

코드 변경 시 관련 문서도 함께 업데이트할 것:
- Java 클래스 추가/삭제 → 해당 모듈 `CLAUDE.md` 업데이트
- 환경변수 추가 → `.claude/rules/env-vars.md` 업데이트
- 새 Facade/Service 추가 → 해당 모듈 `CLAUDE.md` + `.claude/rules/` 업데이트
- Slack 커맨드 추가 → 루트 `CLAUDE.md` 커맨드 테이블 + `ingest/CLAUDE.md` 업데이트
- 대규모 변경 후 → `/update-docs` 커맨드로 일괄 갱신

## 변경 원칙

- **최소 변경**: 요청된 범위의 파일만 수정할 것 — 요청하지 않은 리팩터링, 정리, 개선을 포함하지 말 것
- **새 기능 추가 시 TDD**: 테스트를 먼저 작성하고, 테스트 통과를 목표로 구현할 것 (skills/tdd-workflow 참조)
- **변경 전 영향 확인**: 수정하려는 파일이 다른 모듈에서 참조되는지 확인 후 진행

## 주의사항

- `config/google-credentials.json`은 **민감 정보** — 내용을 출력하거나 수정하지 말 것
- `config/` 파일 구조 변경 시 관련 모듈 코드도 함께 수정할 것
- Jira Cloud REST API: POST `/rest/api/3/search/jql` 사용할 것 (GET `/rest/api/3/search`는 HTTP 410)
- Lambda Shadow JAR: META-INF 서명 파일 제거 필수 (`mergeServiceFiles`)
- 빌드/배포는 **Makefile이 정본** (`make help`로 전체 타겟 확인)
