# Common 모듈

자동화 플랫폼의 모든 상위 모듈에서 공통으로 사용하는 기초 라이브러리입니다.

## 모듈 개요

- **예외 체계**: 플랫폼 전체에서 일관된 Unchecked 예외 클래스 제공
- **인증 추상화**: Bearer/Basic 토큰 제공자 인터페이스 및 구현체
- **도메인 코드**: Jira 상태/우선순위, 근무 상태, 부재 유형 등 Enum 집합
- **날짜/시간 유틸**: KST 기준 날짜 계산, 포맷팅, RFC3339 변환
- **Slack 메시지**: Block Kit JSON 빌더 (Fluent API)
- **공유 모델**: 그룹웨어 계정 정보 값 객체

## 패키지 구조

```
com.riman.automation.common/
├── auth/               # 인증 토큰 제공
│   ├── TokenProvider          # 토큰 인터페이스 (Bearer/Basic 헤더 생성)
│   ├── EnvTokenProvider       # 환경변수 기반 Bearer 토큰
│   └── BasicTokenProvider     # Jira Cloud용 Basic Auth (email:token → Base64)
├── code/               # 도메인 Enum 코드
│   ├── JiraPriorityCode       # Jira 우선순위 (Highest~Lowest, 이모지 매핑)
│   ├── JiraStatusCode         # Jira 상태 (IN_PROGRESS/DONE, 프로젝트별 완료상태명)
│   ├── DueDateUrgencyCode     # 기한 긴급도 (OVERDUE/URGENT/NORMAL/NONE)
│   ├── ReportPeriodCode       # 보고서 주기 (DAILY/WEEKLY/MONTHLY)
│   ├── ReportWeekCode         # 주차 범위 (금요일이면 금주+차주 포함)
│   ├── WorkStatusCode         # 근무 상태 (재택/연차/반차/외근 등, 캘린더 키워드 감지)
│   └── AbsenceTypeCode        # 부재 유형 (기간형/단일일형 구분)
├── exception/          # 예외 계층
│   ├── AutomationException         # 최상위 (errorCode + message)
│   ├── ConfigException             # 환경변수/설정 오류
│   ├── ExternalApiException        # 외부 API 응답 오류 (HTTP 상태 코드 포함)
│   └── ExternalApiClientException  # HTTP 통신 자체 오류 (연결/타임아웃)
├── model/              # 공유 모델
│   └── GroupwareAccountInfo   # 그룹웨어 계정 (Secrets Manager JSON 구조 매핑)
├── slack/              # Slack 유틸
│   └── SlackBlockBuilder      # Block Kit JSON 빌더 (header/section/divider/richText)
└── util/               # 유틸리티
    └── DateTimeUtil           # KST 날짜/시간 (파싱, 포맷, 주차 계산, RFC3339 변환)
```

## 의존성

### 외부 라이브러리

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| `jackson-databind` | 2.16.1 | JSON 직렬화/역직렬화 |
| `jackson-datatype-jsr310` | 2.16.1 | Java 8 시간 타입 지원 |
| `slf4j-api` | 2.0.9 | 로깅 추상화 |
| `lombok` | 1.18.30 | 보일러플레이트 코드 제거 |

### 내부 모듈 의존성

- **없음** (독립적 기초 모듈)
- clients, ingest, worker, scheduler, groupware 모듈이 이 모듈을 의존

## 주요 환경변수

| 변수명 | 용도 | 사용 클래스 |
|--------|------|------------|
| `JIRA_EMAIL` | Jira 계정 이메일 | BasicTokenProvider |
| `JIRA_API_TOKEN` | Jira API 토큰 | BasicTokenProvider |

## 핵심 흐름

### 예외 처리 계층

```
AutomationException (errorCode + message)
 ├─ ConfigException ("CONFIG_ERROR")
 ├─ ExternalApiException ("EXTERNAL_API_ERROR", statusCode 포함)
 └─ ExternalApiClientException ("EXTERNAL_API_CLIENT_ERROR")
```

### 토큰 제공 패턴

```
TokenProvider 인터페이스
 ├─ EnvTokenProvider: System.getenv(name) → Bearer 토큰 (Cold start 시 캐싱)
 └─ BasicTokenProvider: email:token → Base64 → Basic 헤더
```

상위 모듈(scheduler, worker 등)이 구현체를 생성하여 clients 계층에 주입합니다.

### Slack Block Kit 빌더

```java
String json = SlackBlockBuilder.forChannel("C123")
    .fallbackText("일일 보고서")
    .header("일일 팀 보고서")
    .divider()
    .section("*공지*\n- 회식 금요일")
    .build();
```
