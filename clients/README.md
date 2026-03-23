# Clients 모듈

외부 서비스 API를 호출하는 HTTP 클라이언트 래퍼 모듈입니다.

## 모듈 개요

Jira, Slack, Google Calendar, Confluence, Anthropic(Claude) 등 외부 API와의 통신을 캡슐화합니다. 각 클라이언트는 `TokenProvider` 인터페이스를 통해 인증 토큰을 주입받으며, 공통 HTTP 클라이언트(`SharedHttpClient`)를 활용하여 커넥션 풀을 공유합니다.

## 패키지 구조

```
com.riman.automation.clients/
├── http/                   # HTTP 통신 기반
│   ├── SharedHttpClient          # Apache HttpClient 기반 공유 커넥션 풀 (connect 3s, response 10s)
│   ├── BaseHttpClient            # JSON GET/POST 공통 로직 (Jackson 직렬화)
│   └── ApiResponse               # HTTP 응답 값 객체 (statusCode, body, headers)
├── jira/                   # Jira Cloud REST API
│   └── JiraClient                # 이슈 조회, 검색(JQL), 프로젝트 정보, 댓글, 상태 변경
├── slack/                  # Slack Web API
│   └── SlackClient               # chat.postMessage, conversations.open, views.open/update
├── calendar/               # Google Calendar API v3
│   └── GoogleCalendarClient      # 이벤트 CRUD, extendedProperties, S3 서비스 계정 키 로드
├── confluence/             # Confluence Cloud REST API
│   └── ConfluenceClient          # 페이지 CRUD, 자식 페이지 탐색, 파일 첨부 (multipart)
└── anthropic/              # Anthropic Claude API
    └── AnthropicClient           # Messages API 호출 (보고서 AI 다듬기용)
```

## 주요 클래스

### JiraClient

- Basic Auth 인증 (`email:apiToken` Base64)
- JQL 검색, 이슈 상세 조회, 필드 업데이트
- 프로젝트별 상태 카테고리 조회

### SlackClient

- Bearer Token 인증
- DM 채널 열기 (`conversations.open`)
- 메시지 전송 (`chat.postMessage`)
- 모달 열기/업데이트 (`views.open`, `views.update`)
- `ok:false` 응답 → `ExternalApiException` 변환

### GoogleCalendarClient

- Google 서비스 계정 인증 (S3에서 JSON 키 로드)
- 이벤트 목록 조회, 생성, 수정, 삭제
- `extendedProperties`로 Jira 이슈 키 연결
- 시간대: KST (Asia/Seoul) 고정

### ConfluenceClient

- Basic Auth 인증
- 페이지 생성/수정/조회 (Storage Format HTML)
- 자식 페이지 검색 (3단계 탐색: children API → CQL → title 검색)
- 파일 첨부 (multipart/form-data, 동일 파일명 덮어쓰기)

### AnthropicClient

- API Key 인증 (`x-api-key` 헤더)
- Messages API 호출 (system prompt + user message)
- 모델: claude-sonnet (설정 가능)
- max_tokens: 4096

## 의존성

### 내부 모듈

| 모듈 | 용도 |
|------|------|
| `common` | TokenProvider, 예외 클래스, DateTimeUtil |

### 외부 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `jackson-databind` / `jackson-datatype-jsr310` | JSON 처리 |
| `google-api-services-calendar` v3 | Google Calendar API |
| `google-api-client` / `google-auth-library-oauth2-http` | Google OAuth2 |
| `httpclient5` (Apache) | HTTP 커넥션 풀 |
| `slf4j-api` | 로깅 |
| `lombok` | 보일러플레이트 제거 |

## 주요 환경변수

| 변수명 | 용도 | 사용 클라이언트 |
|--------|------|----------------|
| `JIRA_EMAIL` | Jira 계정 이메일 | JiraClient (BasicTokenProvider) |
| `JIRA_API_TOKEN` | Jira API 토큰 | JiraClient (BasicTokenProvider) |
| `SLACK_BOT_TOKEN` | Slack Bot 토큰 | SlackClient (EnvTokenProvider) |
| `ANTHROPIC_API_KEY` | Anthropic API 키 | AnthropicClient |
| `GOOGLE_CALENDAR_CREDENTIALS_BUCKET` | S3 버킷 (Google 인증키) | GoogleCalendarClient |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | S3 키 (Google 인증키) | GoogleCalendarClient |

## 핵심 흐름

### HTTP 클라이언트 구조

```
SharedHttpClient (Apache HttpClient, 커넥션 풀)
    ↓
BaseHttpClient (JSON GET/POST 공통 + Jackson)
    ↓
개별 클라이언트 (JiraClient, SlackClient 등)
    ↓
TokenProvider 주입 (상위 모듈에서 제공)
```

### Confluence 자식 페이지 탐색 (3단계)

```
1단계: children API (인덱싱 완료된 페이지)
  ↓ 실패 시
2단계: CQL 검색 (parent = pageId AND title = "...")
  ↓ 실패 시
3단계: title 검색 후 ancestors 개별 확인
```