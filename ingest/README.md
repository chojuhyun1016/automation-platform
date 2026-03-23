# Ingest 모듈

Slack 슬래시 커맨드와 Jira 웹훅을 수신하여 검증 후 SQS로 전달하는 Lambda 진입점 모듈입니다.

## 모듈 개요

API Gateway 뒤에서 동작하는 AWS Lambda 함수로, 다음 역할을 수행합니다:

- **Slack 서명 검증** (HMAC-SHA256)으로 정당한 요청만 처리
- **5개 슬래시 커맨드** 처리: `/재택근무`, `/부재등록`, `/계정관리`, `/일정등록`, `/현재티켓`
- **Slack 모달 UI** 표시 및 제출 처리
- **Jira 웹훅** 수신 후 SQS 메시지로 변환
- **Lambda 콜드스타트 최적화**: 병렬 초기화로 ~904ms 달성 (기존 ~2585ms 대비 65% 단축)

## 패키지 구조

```
com.riman.automation.ingest/
├── handler/
│   └── IngestHandler              # Lambda 진입점 (API Gateway + EventBridge 호환)
├── facade/                        # 요청별 처리 Facade
│   ├── SlackFacade                # Slack 요청 통합 라우팅 (병렬 초기화)
│   ├── JiraWebhookFacade          # Jira 이벤트 수신 → SQS 전달
│   ├── AccountManageFacade        # /계정관리 (등록/변경/삭제)
│   ├── ScheduleManageFacade       # /일정등록 (등록/삭제)
│   └── CurrentTicketFacade        # /현재티켓 (캘린더 기반 미완료 티켓 조회)
├── service/
│   ├── WorkerMessageService       # SQS 메시지 발행 (싱글톤)
│   ├── SlackApiService            # Slack API 호출 (모달, DM)
│   ├── GroupwareCredentialService  # Secrets Manager 계정 CRUD
│   ├── PasswordEncryptionService  # KMS + AES-256-GCM 암호화
│   └── ScheduleMappingQueryService # DynamoDB 일정 매핑 조회
├── security/
│   └── SlackSignatureVerifier     # HMAC-SHA256 서명 검증 (300초 만료)
├── dto/                           # Slack 요청/응답 DTO
├── payload/                       # Slack Modal Block Kit 빌더
└── util/
    └── HttpResponse               # Lambda 응답 생성 유틸
```

## 의존성

### 내부 모듈

| 모듈 | 용도 |
|------|------|
| `common` | GroupwareAccountInfo, 예외 클래스, DateTimeUtil, AbsenceTypeCode |
| `clients` | SlackClient, GoogleCalendarClient |

### 외부 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `aws-lambda-java-core` / `aws-lambda-java-events` | Lambda 런타임 |
| AWS SDK v2: `sqs`, `s3`, `secretsmanager`, `kms`, `dynamodb` | AWS 서비스 |
| `google-api-services-calendar` v3 | Google Calendar API |
| `jackson-databind` / `jackson-datatype-jsr310` | JSON 처리 |
| `slf4j-simple` | 로깅 |

## 주요 환경변수

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `SQS_QUEUE_URL` | Worker 모듈용 SQS 큐 URL | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |
| `SLACK_SIGNING_SECRET` | Slack 서명 검증 시크릿 | O |
| `GROUPWARE_CREDENTIALS_SECRET` | Secrets Manager 시크릿명 | - |
| `KMS_KEY_ID` | KMS 키 ID (비밀번호 암호화) | - |
| `SCHEDULE_MAPPING_TABLE` | DynamoDB 테이블명 | - |
| `TICKET_CALENDAR_ID` | Google Calendar ID (티켓 조회) | - |
| `CONFIG_BUCKET` | S3 버킷 (설정 파일) | - |
| `TEAM_MEMBERS_KEY` | S3 키 (team-members.json) | - |

## 핵심 흐름

### 요청 라우팅

```
API Gateway / EventBridge
    ↓
IngestHandler.handleRequest()
    ├─ /warmup           → 200 OK (Lambda warm 유지)
    ├─ /slack/*          → SlackFacade.handle()
    │   ├─ Slack 서명 검증 (HMAC-SHA256)
    │   ├─ 슬래시 커맨드  → 모달 열기
    │   ├─ view_submission → 모달 제출 처리
    │   └─ block_actions  → 버튼 클릭 처리
    └─ /webhook/jira     → JiraWebhookFacade.handle()
        └─ SQS 메시지 전송
```

### 병렬 초기화 (SlackFacade 생성자)

```
Thread Pool (5개)
 ├─ WorkerMessageService     (SQS 클라이언트 ~661ms)
 ├─ SlackApiService          (HTTP 커넥션 풀 ~904ms) ← 병목
 ├─ AccountManageFacade      (KMS 클라이언트 ~268ms)
 ├─ ScheduleManageFacade     (DynamoDB pre-warm ~747ms)
 └─ CurrentTicketFacade      (SlackClient 주입 ~5ms)

결과: max(661, 904, 268, 747, 5) ≈ 904ms (순차 대비 65% 단축)
```

### Slack 커맨드 → SQS 메시지 흐름

```
/재택근무 → 모달 → 제출 → WorkerMessageService.sendRemoteWork() → SQS
/부재등록 → 모달 → 제출 → WorkerMessageService.sendAbsence() → SQS
/일정등록 → 모달 → 제출 → WorkerMessageService.sendScheduleRegister() → SQS
/계정관리 → 모달 → 제출 → Secrets Manager 직접 저장
/현재티켓 → 모달 → 제출 → Google Calendar 조회 → Slack DM 전송
```