# clients 모듈

외부 API 클라이언트. common에만 의존한다.
**상위 모듈 코드를 참조하지 말 것.**

## 패키지 구조

```
com.riman.automation.clients
├── http/         SharedHttpClient, BaseHttpClient, ApiResponse
├── slack/        SlackClient
├── jira/         JiraClient
├── calendar/     GoogleCalendarClient
├── confluence/   ConfluenceClient
└── anthropic/    AnthropicClient
```

## HTTP 기반 계층

```
SharedHttpClient (static 싱글톤)
  └── connect 3초, response 10초, Lambda warm TCP 재사용

BaseHttpClient (추상)
  ├── get(url, headers) → ApiResponse
  ├── post(url, headers, body) → ApiResponse
  └── requireSuccess(response, context) → ExternalApiException throw
      PUT 미지원 — ConfluenceClient에서 HttpURLConnection 직접 사용
```

## 클라이언트별 인증

| 클라이언트 | 인증 | 생성 비용 | 캐싱 필수 |
|-----------|------|----------|----------|
| SlackClient | Bearer (EnvTokenProvider) | 낮음 | 아니오 |
| JiraClient | Basic (BasicTokenProvider) | 낮음 | 아니오 |
| GoogleCalendarClient | 서비스 계정 (S3 credentials) | **~1200ms** | **필수** |
| ConfluenceClient | Basic Auth | 낮음 | 아니오 |
| AnthropicClient | API Key (x-api-key) | 낮음 | 아니오 |

GoogleCalendarClient는 반드시 `static volatile`로 캐싱할 것.

## ConfluenceClient 3단계 검색

인덱싱 지연 대응:
1. CQL `parent=` + `title=` (인덱싱 지연 취약)
2. Title 검색 + ancestors 직접 검증
3. Children API 폴백

중복 페이지: 자식이 있는 것 우선, 동일하면 ID 낮은 것.

## JiraClient

- POST `/rest/api/3/search/jql` 사용 (GET은 HTTP 410)
- `search(jql, fields, maxResults)` → JSON 응답 (nextPageToken 자동 페이지네이션, 100건씩)
- `getIssue(issueKey, fields)` → 단일 이슈
- **startAt을 body에 포함하면 400 오류** (절대 금지)

## AnthropicClient

- API: `https://api.anthropic.com/v1/messages` (버전 2023-06-01)
- 기본 모델: `claude-sonnet-4-20250514`, max_tokens: 4096
- `complete(systemPrompt, userMessage)` → 텍스트 응답
- 인증: `x-api-key` 헤더 (EnvTokenProvider)

## SlackClient

- `postMessage()`, `openView()`, `updateView()`, `openDm()`, `getUserRealName()`
- Slack HTTP 200이어도 `ok:false` 가능 → 내부 assertSlackOk() 검증
