# ingest 모듈

Slack 커맨드 및 Jira 웹훅의 Lambda 진입점.
API Gateway → IngestHandler → SlackFacade / JiraWebhookFacade.

## 패키지 구조

```
com.riman.automation.ingest
├── handler/    IngestHandler (Lambda 진입점)
├── facade/     SlackFacade, AccountManageFacade, CurrentTicketFacade,
│               ScheduleManageFacade, LunchCardFacade, JiraWebhookFacade
├── dto/
│   ├── slack/  SlackCommandRequest, AbsenceModalSubmit, RemoteWorkModalSubmit,
│   │           AccountModalSubmit, ScheduleModalSubmit, CurrentTicketModalSubmit,
│   │           LunchCardModalSubmit
│   └── jira/   JiraWebhookEvent
├── payload/    AbsenceModalBuilder, RemoteWorkModalBuilder, AccountModalBuilder,
│               ScheduleModalBuilder, CurrentTicketModalBuilder, LunchCardModalBuilder
├── security/   SlackSignatureVerifier (HMAC-SHA256, 5분 유효)
├── service/    SlackApiService, WorkerMessageService, PasswordEncryptionService,
│               GroupwareCredentialService, ScheduleMappingQueryService
└── util/       HttpResponse
```

## 요청 흐름

```
IngestHandler (Map<String, Object>)
├── /warmup          → 200 "warm"
├── /slack/*         → SlackFacade.handle()
│   ├── Retry 감지   → X-Slack-Retry-Num + Reason 둘 다 확인
│   ├── payload=     → 인터랙션 (callback_id 라우팅)
│   │   ├── absence_submit         → AbsenceFacade (SQS)
│   │   ├── remote_work_submit     → RemoteWorkFacade (SQS)
│   │   ├── account_manage_submit  → AccountManageFacade
│   │   ├── schedule_submit        → ScheduleManageFacade (SQS)
│   │   ├── current_ticket_submit  → CurrentTicketFacade
│   │   └── lunch_card_submit      → LunchCardFacade (SQS)
│   ├── block_actions → action_id 라우팅
│   │   ├── action_lunch_card_date   → LunchCardFacade (날짜 변경 → views.update)
│   │   └── action_lunch_card_toggle → LunchCardFacade (주/월 토글 → views.update)
│   └── 슬래시 커맨드 → 모달 열기
├── /webhook/jira    → JiraWebhookFacade.handle() (SQS)
└── 기타             → 404
```

## 핵심 패턴

### 병렬 초기화 (SlackFacade 생성자)
`ExecutorService(5)` + `CompletableFuture`로 5개 의존성 병렬 생성.
순차 ~2.5초 → 병렬 ~900ms.

### SQS 위임 + join()
```java
Thread sqsThread = new Thread(() -> workerMessageService.send(...));
sqsThread.start();
sqsThread.join();       // SQS 전송 보장
return HttpResponse.ok("");
```

### Pre-warm 데몬 (CurrentTicketFacade)
기간별 조회: daily(일별), weekly(주별), monthly(월별), quarterly(분기별).
handleCommand()에서 데몬 스레드로 CalendarClient 초기화 → handleModalSubmit()에서 join(2500).

### Static Volatile 캐싱
S3Client ~300ms, CalendarClient ~1200ms, TeamMemberMap ~100ms 절약.

### 듀얼 생성자 (DI)
```java
public Facade()                        // 독립 사용
public Facade(SlackApiService shared)  // SlackFacade에서 주입
```

## HttpResponse 유틸리티

- `modalError(blockId, message)`: **block_id** 키 사용 (action_id 아님)
- `modalResult(success, message, title)`: response_action=update
- `ok()`, `badRequest()` 등: 표준 HTTP 응답
- **에러 시 항상 200 반환** — 500은 Slack 재시도 루프 + {reason} 미치환 버그

## 싱글톤

- `WorkerMessageService.getInstance()`: static SqsClient 공유
- `ScheduleMappingQueryService`: DynamoDB Pre-warm (DescribeTable, ~1500ms 절약)
