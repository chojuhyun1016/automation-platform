# common 모듈

공통 라이브러리. 예외, Enum, 유틸리티, Slack Block Kit 빌더를 제공한다.
모든 상위 모듈(clients, ingest, worker, scheduler, groupware)이 의존한다.

**상위 모듈 코드를 참조하지 말 것.**

## 패키지 구조

```
com.riman.automation.common
├── auth/          TokenProvider, EnvTokenProvider, BasicTokenProvider
├── code/          AbsenceTypeCode, WorkStatusCode, JiraStatusCode, JiraPriorityCode,
│                  DueDateUrgencyCode, ReportWeekCode, ReportPeriodCode
├── exception/     AutomationException, ConfigException, ExternalApiException,
│                  ExternalApiClientException
├── model/         GroupwareAccountInfo
├── slack/         SlackBlockBuilder
└── util/          DateTimeUtil
```

## 예외 계층

| 클래스 | errorCode | 사용 시점 |
|--------|-----------|----------|
| `AutomationException` | 임의 | 비즈니스 규칙 위반 (최상위 unchecked) |
| `ConfigException` | `CONFIG_ERROR` | S3 설정/환경변수 로드 실패 |
| `ExternalApiException` | `EXTERNAL_API_ERROR` | HTTP 응답 수신 후 오류 (4xx/5xx, Slack ok:false) |
| `ExternalApiClientException` | `EXTERNAL_API_CLIENT_ERROR` | HTTP 응답 수신 전 오류 (연결/타임아웃/파싱) |

새로운 예외 클래스를 만들지 말 것.

## Enum 코드

| Enum | 팩토리 | 핵심 값 |
|------|--------|---------|
| `AbsenceTypeCode` | `fromLabel(String)` | 기간형 7개 + 단일일형 6개 (`isSingleDayOnly()`) |
| `WorkStatusCode` | `detect(String title)` | OFFICE, REMOTE, ANNUAL_LEAVE, HALF_AM/PM 등 |
| `JiraStatusCode` | — | IN_PROGRESS, DONE (`DONE_STATUS_NAMES`로 프로젝트별 관리) |
| `JiraPriorityCode` | `fromName(String)` | HIGHEST~LOWEST (이모지 포함) |
| `DueDateUrgencyCode` | `from(LocalDate, LocalDate)` | OVERDUE, URGENT(3일), NORMAL, NONE |
| `ReportPeriodCode` | — | DAILY, WEEKLY, MONTHLY |
| `ReportWeekCode` | — | THIS_WEEK, THIS_AND_NEXT_WEEK (금요일 확장) |

## TokenProvider

```
TokenProvider (interface)
├── getToken()        → 토큰 값 (prefix 없음)
├── toBearerHeader()  → "Bearer {token}"
└── toBasicHeader()   → "Basic {base64}"

EnvTokenProvider(envName)  → System.getenv() → Slack, Confluence, Anthropic
BasicTokenProvider         → JIRA_EMAIL + JIRA_API_TOKEN → Jira Cloud
```

## SlackBlockBuilder

```java
SlackBlockBuilder.builder()
    .forChannel(channelId)   // 또는 .forModal()
    .header("제목")
    .section("*mrkdwn*")
    .divider()
    .context("부가 정보")
    .rawBlock(ObjectNode)    // 커스텀 JSON
    .richText(ArrayNode)     // rich_text (색상)
    .fallbackText("알림 텍스트")
    .noUnfurl()
    .build();                // JSON 직렬화
```

- `blockCount()`: Slack 50개 한도 모니터링
- 들여쓰기: 전각 공백 (U+3000)

## DateTimeUtil

- `todayKst()`, `nowKst()`: KST 기준
- 날짜 계산은 항상 KST 기준으로 할 것
