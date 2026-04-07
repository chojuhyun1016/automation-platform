---
paths:
  - common/**
  - clients/**
---

# common / clients 모듈 규칙

## 의존성 방향

`common ← clients ← 상위 모듈`
- common과 clients에서 상위 모듈(ingest, worker 등)의 코드를 참조하지 말 것

## 예외 계층 (common/exception/)

| 클래스 | 용도 | 사용 시점 |
|--------|------|----------|
| `AutomationException` | 비즈니스 예외 (unchecked) | 비즈니스 규칙 위반, errorCode 포함 |
| `ConfigException` | 설정 오류 | S3 설정 파일/환경변수 로드 실패 |
| `ExternalApiException` | HTTP 응답 오류 | 4xx/5xx 응답, Slack `ok:false` |
| `ExternalApiClientException` | 통신 실패 | 연결/타임아웃/파싱 오류 (HTTP 응답 이전 단계) |

### 사용 규칙
- 새로운 예외 클래스를 만들지 말 것 — 위 4가지로 커버할 것
- `ExternalApiException`은 HTTP 응답을 받은 경우, `ExternalApiClientException`은 못 받은 경우에 사용
- `ConfigException`은 fallback 없이 즉시 실패가 올바른 동작인 설정에 사용

## Enum 코드 (common/code/)

| Enum | 팩토리 메서드 | 주요 값 |
|------|-------------|---------|
| `JiraStatusCode` | — | `IN_PROGRESS`, `DONE` |
| `JiraPriorityCode` | `fromName(String)` | HIGHEST, HIGH, MEDIUM, LOW, LOWEST (이모지 포함) |
| `DueDateUrgencyCode` | `from(LocalDate, LocalDate)` | OVERDUE, URGENT(3일이내), NORMAL, NONE |
| `WorkStatusCode` | `detect(String eventTitle)` | OFFICE, REMOTE, ANNUAL_LEAVE, HALF_AM/PM 등 |
| `AbsenceTypeCode` | `fromLabel(String)` | ANNUAL_LEAVE, SICK_LEAVE, AM_HALF, PM_HALF 등 |
| `ReportWeekCode` | — | THIS_WEEK, THIS_AND_NEXT_WEEK (금요일 확장) |
| `ReportPeriodCode` | — | DAILY, WEEKLY, MONTHLY |

- Enum에 새 값을 추가할 때 팩토리 메서드의 매핑도 함께 갱신할 것
- `AbsenceTypeCode.isSingleDayOnly()`로 반차 등 단일일 유형 판별

## SlackBlockBuilder (common/slack/)

Slack Block Kit JSON을 빌드하는 체이닝 API:
```java
SlackBlockBuilder.builder()
    .forChannel(channelId)  // 채널 메시지용
    .header("제목")
    .divider()
    .section("*mrkdwn 텍스트*")
    .context("부가 정보")
    .rawBlock(customNode)   // 커스텀 JSON 블록
    .richText(elements)     // rich_text 블록 (색상 지정 가능)
    .noUnfurl()
    .build();
```

| 메서드 | 용도 |
|--------|------|
| `forChannel(id)` / `forModal()` | 대상 설정 |
| `header()`, `section()`, `divider()`, `context()` | 기본 블록 |
| `rawBlock(ObjectNode)` | 커스텀 JSON 블록 추가 |
| `richText(ArrayNode)` | rich_text 블록 (색상 지정) |
| `blockCount()` | 블록 수 조회 (Slack 50개 한도 모니터링) |
| `fallbackText()` | 알림 대체 텍스트 |
| `noUnfurl()` | URL 미리보기 비활성화 |

- 들여쓰기: 전각 공백 (U+3000) 사용

## DateTimeUtil (common/util/)

- `todayKst()`: KST 기준 오늘 `LocalDate`
- `nowKst()`: KST 기준 현재 `ZonedDateTime`
- ISO 형식 파싱/포맷 제공
- 날짜 계산은 항상 KST 기준으로 할 것

## HTTP 클라이언트 계층 (clients/http/)

### SharedHttpClient
- 모든 Client가 공유하는 HTTP 클라이언트 (static 싱글톤)
- 타임아웃: connect 3초, response 10초
- Lambda warm 상태에서 TCP 연결 재사용

### BaseHttpClient
모든 API 클라이언트의 기반 클래스:
- `get(url, headers)` → `ApiResponse`
- `post(url, headers, body)` → `ApiResponse`
- PUT은 미제공 — ConfluenceClient에서 `HttpURLConnection` 직접 사용
- `requireSuccess(response, context)`: 실패 시 `ExternalApiException` throw

## TokenProvider 계층 (common/auth/)

| 구현 | 인증 방식 | 용도 |
|------|----------|------|
| `EnvTokenProvider(envName)` | `System.getenv()` → Bearer | Slack, Confluence, Anthropic |
| `BasicTokenProvider` | `JIRA_EMAIL` + `JIRA_API_TOKEN` → Basic Auth | Jira Cloud |

- `getToken()`: 토큰 값만 반환 (prefix 없음)
- `toBearerHeader()`: `"Bearer {token}"`
- `toBasicHeader()`: `"Basic {base64}"`
- 미설정 시 `ConfigException` throw

## API 클라이언트 인증 패턴

| 클라이언트 | 인증 | 생성 비용 |
|-----------|------|----------|
| `SlackClient` | Bearer Token (`EnvTokenProvider`) | 낮음 |
| `JiraClient` | Basic Auth (email + API token, `BasicTokenProvider`) | 낮음 |
| `GoogleCalendarClient` | 서비스 계정 (S3 credentials JSON, `byte[]`) | **높음 ~1200ms** |
| `ConfluenceClient` | Basic Auth (Atlassian 계정 공유) | 낮음 |
| `AnthropicClient` | API Key (`x-api-key` 헤더) | 낮음 |

- GoogleCalendarClient는 반드시 `static volatile`로 캐싱할 것
- SlackClient는 `EnvTokenProvider("SLACK_BOT_TOKEN")` 패턴 사용

## ConfluenceClient 3단계 검색 전략

인덱싱 지연 대응:
1. CQL `parent=` + `title=` 검색 (인덱싱 지연에 취약)
2. Title 검색 + ancestors 직접 검증 (인덱싱 지연 없음)
3. Children API 폴백 (최후 수단)

중복 페이지 발견 시: 자식 페이지가 있는 것 우선, 동일하면 ID 낮은 것 선택.

## GroupwareAccountInfo (common/model/)

Secrets Manager 저장 구조:
```json
{
  "slack_user_id": "U07...",
  "groupware_id": "R00365",
  "groupware_password": "ENC:base64.base64.base64",
  "registered_at": "2026-03-04T10:30:00+09:00"
}
```
- `ENC:` 접두사: KMS 봉투 암호화 (AES-256-GCM)
- 레거시 데이터는 평문 → 다음 upsert 시 자동 암호화 마이그레이션