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
├── common/          # 공통 라이브러리 (예외, Enum, 유틸리티)
├── clients/         # 외부 API 클라이언트 (Jira, Slack, Calendar, Confluence, Anthropic)
├── ingest/          # Lambda 진입점 (Slack 커맨드, Jira 웹훅 수신)
├── worker/          # SQS 소비자 (Jira-Calendar 동기화, 재택/부재/일정 처리)
├── scheduler/       # EventBridge 스케줄러 (일일/주간/월간 보고서)
├── groupware/       # Fargate 오케스트레이터 (그룹웨어 부재 신청)
├── groupware-bot/   # Python Playwright (브라우저 자동화, Gradle 미포함)
└── config/          # S3 업로드용 런타임 설정 파일
```

## 의존성 방향

```
common ← clients ← ingest / worker / scheduler / groupware
```

- `common`과 `clients`는 하위 모듈이므로 상위 모듈(ingest, worker 등)의 코드를 참조하면 안 됨
- `groupware-bot`은 독립적인 Python 프로젝트 (Gradle 멀티모듈에 포함되지 않음)

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
```

## 코딩 컨벤션

### Java

- **Java 17** 문법 사용 (record, sealed class, text block, pattern matching 등)
- **Lombok** 사용: `@Slf4j`, `@Getter`, `@Builder`, `@RequiredArgsConstructor`
- **패키지 구조**: `com.riman.automation.<module>.<layer>.<class>`
  - layer: `handler`, `facade`, `service`, `dto`, `payload`, `security`, `util`
- **예외 처리**: `common` 모듈의 `AutomationException`, `ConfigException`, `ExternalApiException` 사용
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

## AWS 서비스 사용

| 서비스 | 용도 |
|--------|------|
| Lambda | ingest, worker, scheduler, groupware 모듈 실행 |
| SQS | ingest → worker, worker → groupware 비동기 메시지 전달 |
| S3 | 설정 파일, Lambda JAR 배포, 스크린샷 저장 |
| DynamoDB | Jira-Calendar 매핑, 일정 매핑, 중복 방지 |
| Secrets Manager | Slack Bot Token, 그룹웨어 계정 정보 |
| KMS | 그룹웨어 비밀번호 AES-256-GCM 암호화 |
| ECS Fargate | groupware-bot Docker 컨테이너 실행 |
| ECR | groupware-bot Docker 이미지 저장 |
| EventBridge | Scheduler Lambda 정기 트리거 |

## 외부 API

| API | 클라이언트 | 인증 방식 |
|-----|----------|----------|
| Slack Web API | `SlackClient` | Bearer Token (Bot Token) |
| Jira REST API v3 | `JiraClient` | Basic Auth (email + API token) |
| Google Calendar API v3 | `GoogleCalendarClient` | 서비스 계정 (google-credentials.json) |
| Confluence REST API | `ConfluenceClient` | Basic Auth (Atlassian 계정 공유) |
| Anthropic Claude API | `AnthropicClient` | API Key (x-api-key 헤더) |

## 테스트

현재 유닛 테스트는 구성되어 있지 않음. 변경사항은 `make build`로 컴파일 검증.

## 주의사항

- `config/google-credentials.json`은 민감 정보 — 내용을 출력하거나 수정하지 말 것
- `config/` 디렉토리의 파일은 S3에 업로드되어 런타임에 사용됨 — 구조 변경 시 관련 모듈 코드도 함께 수정 필요
- Jira Cloud REST API는 POST `/rest/api/3/search/jql` 사용 (GET `/rest/api/3/search`는 HTTP 410 반환)
- Confluence Cloud는 인덱싱 지연 이슈로 3단계 페이지 검색 전략 적용 중
- Lambda Shadow JAR 빌드 시 META-INF 서명 파일 제거 필수 (`mergeServiceFiles`)
