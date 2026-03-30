---
paths:
  - ingest/**/handler/**
  - worker/**/handler/**
  - scheduler/**/handler/**
  - groupware/**/handler/**
  - ingest/**/facade/SlackFacade*
  - ingest/**/facade/CurrentTicket*
---

# Lambda 아키텍처 패턴

## 핵심 원칙: Lambda HTTP 응답 타이밍

**Lambda는 `handleRequest()` 리턴 후에만 HTTP 응답을 전송한다.**
- `Thread.join()`은 `handleRequest()` 리턴을 차단하여 HTTP 응답을 지연시킴
- join 없이 스레드를 시작하면 Lambda가 응답 후 컨테이너를 freeze → 스레드가 중단됨 (비신뢰)

## Slack 3초 제한

`view_submission` 등 Slack 인터랙션은 3초 내 HTTP 응답 필수.

**3초 내 완료 가능한 작업**: 직접 처리 후 리턴
**3초 초과 예상 작업**: 아래 패턴 중 선택

### 패턴 1: SQS 위임 + join()
```java
Thread sqsThread = new Thread(() -> sendToSQS(...));
sqsThread.start();
sqsThread.join();         // SQS 전송 완료까지 대기
return HttpResponse.ok(""); // Slack에 200 응답
```
- SQS 전송 (~100-500ms) 후 worker가 실제 처리
- 대부분의 Slack 커맨드에서 사용

### 패턴 2: join(timeout)
```java
Thread worker = new Thread(() -> heavyWork(...));
worker.start();
worker.join(2500);        // 최대 2.5초 대기
return response;          // 타임아웃 여부 무관하게 응답
```
- CurrentTicketFacade에서 사용 (Calendar 조회 + DM 전송)
- 타임아웃 시 스레드는 다음 warm 호출에서 재개

### 패턴 3: Pre-warm 데몬 스레드
```java
// handleCommand() — 모달 열 때
Thread preWarm = new Thread(() -> getOrCreateCalendarClient());
preWarm.setDaemon(true);
preWarm.start();
return HttpResponse.ok(""); // 모달만 열고 즉시 응답

// handleModalSubmit() — 사용자가 제출할 때 (1-3초 후)
// cachedCalendarClient가 이미 초기화되어 있을 가능성 높음
```
- 사용자의 모달 조작 시간(1-3초)을 활용
- `setDaemon(true)`: Lambda 응답 차단 방지

## Static Volatile 캐싱

Lambda 컨테이너는 warm 호출 시 재사용되므로 static 필드가 유지된다.

```java
private static volatile S3Client cachedS3Client;           // ~300ms 절약
private static volatile GoogleCalendarClient cachedClient;  // ~1200ms 절약
```

### 캐싱 규칙
- 생성 비용 300ms 이상인 객체는 `static volatile`로 캐싱할 것
- `volatile`: 데몬 스레드 ↔ 핸들러 스레드 간 가시성 보장
- null 체크 후 생성 (lazy initialization)
- 데몬 스레드에서 초기화 실패 시 핸들러에서 재시도

## 핸들러 입력 타입

| 모듈 | 입력 타입 | 이유 |
|------|----------|------|
| ingest | `Map<String, Object>` | API Gateway + EventBridge 양쪽 처리 |
| worker | `SQSEvent` | SQS 트리거 전용 |
| scheduler | `Map<String, Object>` | EventBridge의 빈 문자열 "time" 필드 파싱 오류 방지 |
| groupware | `SQSEvent` | SQS 트리거 전용 |

## 에러 처리 원칙

- Slack 응답: 항상 200 반환 — 500은 Slack 재시도 루프 + `{reason}` 미치환 버그 유발
- SQS 소비자: 처리 불가능한 에러만 throw (DLQ로 이동) — 일시적 실패는 로그 후 계속
- 핸들러 예외: 최외곽에서 catch하여 로깅 후 적절한 응답 반환
