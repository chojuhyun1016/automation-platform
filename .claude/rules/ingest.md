---
paths:
  - ingest/**
---

# ingest 모듈 규칙

## 아키텍처 개요

ingest는 Slack 커맨드 및 Jira 웹훅의 Lambda 진입점이다.
API Gateway → IngestHandler → SlackFacade/JiraWebhookFacade 로 라우팅된다.

## IngestHandler 요청 라우팅

- `Map<String, Object>` 입력을 받아 path 기반으로 라우팅한다
- `/slack` → SlackFacade, `/jira` → JiraWebhookFacade
- 에러 시 항상 200을 반환할 것 — 500은 Slack에서 `{reason}` 미치환 버그를 유발함

## SlackFacade 요청 판별 순서

1. Slack 재시도 감지: `X-Slack-Retry-Num` + `X-Slack-Retry-Reason == "http_timeout"` **둘 다** 확인할 것
2. body가 `payload=`로 시작하면 인터랙션 (block_actions / view_submission)
3. 그 외 슬래시 커맨드

## 병렬 초기화 패턴 (SlackFacade 생성자)

- `ExecutorService.newFixedThreadPool(5)` + `CompletableFuture`로 facade들을 병렬 초기화
- 순차 ~2.5초 → 병렬 ~900ms
- SlackApiService 인스턴스를 공유하여 SlackClient 중복 생성을 방지할 것

## Facade 공통 패턴

### 듀얼 생성자 (DI)
```java
public AccountManageFacade()                          // 독립 사용
public AccountManageFacade(SlackApiService shared)    // SlackFacade에서 주입
```
- SlackFacade에서 생성할 때는 공유 SlackApiService를 주입할 것

### SQS 위임 + join() 패턴
```java
Thread sqsThread = new Thread(() -> workerMessageService.sendAbsence(...));
sqsThread.start();
sqsThread.join();       // Lambda 응답 차단 — SQS 전송 보장
return HttpResponse.ok("");
```
- `join()` 없이 리턴하면 Lambda가 컨테이너를 freeze하여 SQS 전송이 보장되지 않음

### Block Actions 처리
- HTTP 응답으로는 모달을 업데이트할 수 없음 → `views.update` API를 직접 호출 후 200 반환

## CurrentTicketFacade (특수 패턴)

### Static Volatile 캐싱
```java
private static volatile S3Client cachedS3Client;
private static volatile GoogleCalendarClient cachedCalendarClient;
private static volatile Map<String, String> cachedTeamMemberMap;
```
- warm 호출 시 S3Client ~300ms, CalendarClient ~1200ms 절약

### Pre-warm 데몬 스레드
- `handleCommand()`: 모달 열기 + 데몬 스레드로 CalendarClient 초기화 시작
- `handleModalSubmit()`: `worker.join(2500)` — 최대 2.5초 대기 후 200 반환
- 데몬 스레드는 Lambda freeze 시 일시중지, 다음 호출에서 재개됨

### 티켓 조회 전략
- Google Calendar API `searchQuery`는 결과 누락 가능 → 전체 이벤트 fetch 후 Java에서 필터링할 것
- 분기 전체를 조회 후 period(daily/weekly/quarterly)로 필터링

## WorkerMessageService (싱글톤)

```java
private static final WorkerMessageService INSTANCE = new WorkerMessageService();
private static final SqsClient SQS_CLIENT = SqsClient.builder().build();
```
- `getInstance()`로 접근, SQS 클라이언트는 Lambda 컨테이너 수명 동안 재사용

## HttpResponse 유틸리티

- `modalError(blockId, message)`: `response_action: "errors"` — **block_id** 키를 사용할 것 (action_id 아님)
- `modalResult(success, message, title)`: `response_action: "update"` — 결과 화면 교체
- `jiraAccepted()`: Jira 웹훅 응답 (eventId + messageId 포함)

## JiraWebhookFacade

- 최소한의 파싱 → 유효성 검증 → eventId/receivedAt 메타데이터 추가 → SQS 위임
- `WorkerMessageService.getInstance()` 사용 (new 아님)