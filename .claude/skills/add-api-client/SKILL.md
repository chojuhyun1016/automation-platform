---
name: add-api-client
description: 새 외부 API 클라이언트 추가 절차. BaseHttpClient 상속, 인증, 에러 처리, static 캐싱, 문서 동기화.
---

# 외부 API 클라이언트 추가 절차

## Step 1. 클라이언트 설계

기존 5개 클라이언트 참고:

| 클라이언트 | 인증 | 생성 비용 | 캐싱 |
|-----------|------|----------|------|
| SlackClient | Bearer (EnvTokenProvider) | 낮음 | 불필요 |
| JiraClient | Basic (BasicTokenProvider) | 낮음 | 불필요 |
| GoogleCalendarClient | 서비스 계정 (S3 credentials) | **~1200ms** | **필수** |
| ConfluenceClient | Basic Auth | 낮음 | 불필요 |
| AnthropicClient | API Key (x-api-key) | 낮음 | 불필요 |

## Step 2. 클래스 생성 (clients/ 모듈)

```java
// clients/src/main/java/com/riman/automation/clients/{서비스명}/{서비스명}Client.java
public class {서비스명}Client extends BaseHttpClient {

    public {서비스명}Client(TokenProvider token) {
        super("{서비스명}");
        // 인증 헤더 설정
    }
}
```

### 인증 패턴 선택

| 인증 방식 | TokenProvider | 사용 예 |
|----------|-------------|--------|
| Bearer Token | `EnvTokenProvider("ENV_VAR")` | Slack, Anthropic |
| Basic Auth | `BasicTokenProvider("EMAIL_VAR", "TOKEN_VAR")` | Jira, Confluence |
| API Key 헤더 | `EnvTokenProvider` + 커스텀 헤더 | Anthropic (x-api-key) |
| 서비스 계정 | S3에서 credentials 로드 | Google Calendar |

## Step 3. 에러 처리

BaseHttpClient 규칙 준수:
- HTTP 응답 수신 후 실패 → `ExternalApiException` (apiName, statusCode)
- 연결 실패/타임아웃 → `ExternalApiClientException` (apiName)
- `requireSuccess(response, context)` 사용

**새로운 예외 클래스를 만들지 말 것 — 4가지로 커버.**

## Step 4. Static Volatile 캐싱 (생성 비용 300ms+)

생성 비용이 높은 클라이언트:
```java
private static volatile {서비스명}Client cached{서비스명}Client;

private {서비스명}Client getOrCreate{서비스명}Client() {
    if (cached{서비스명}Client == null) {
        cached{서비스명}Client = new {서비스명}Client(...);
    }
    return cached{서비스명}Client;
}
```

## Step 5. 상위 모듈에서 사용

clients 모듈은 상위 모듈(ingest, worker, scheduler, groupware)에서 사용:
- clients가 상위 모듈을 참조하지 말 것 (의존성 방향 준수)
- 상위 모듈의 Facade/Service에서 클라이언트 인스턴스 생성/주입

## Step 6. 환경변수

- API 키/토큰: 환경변수로 관리 (`System.getenv`)
- Secrets Manager 사용 시: 시크릿 이름만 환경변수로, 값은 런타임 조회
- **하드코딩 절대 금지**

## Step 7. 문서 동기화

- [ ] clients/CLAUDE.md: 클라이언트 목록 + 인증 방식 테이블 업데이트
- [ ] .claude/rules/common-clients.md: API 클라이언트 인증 패턴 테이블 업데이트
- [ ] .claude/rules/env-vars.md: 새 환경변수 추가
- [ ] CLAUDE.md: AWS 핵심 서비스 목록 업데이트 (새 서비스 추가 시)
