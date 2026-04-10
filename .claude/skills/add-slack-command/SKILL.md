---
name: add-slack-command
description: 새 Slack 슬래시 커맨드를 추가하는 단계별 절차. DTO, ModalBuilder, Facade, 라우팅, SQS 위임, 문서 동기화까지.
---

# 새 Slack 슬래시 커맨드 추가 절차

## Step 1. 요구사항 분석

- 커맨드명: `/새커맨드`
- ingest에서 직접 처리 가능한가? (3초 이내)
- SQS 위임이 필요한가? (worker 처리)
- 그룹웨어 연동이 필요한가? (groupware → Fargate)

## Step 2. DTO 생성 (ingest/dto/slack/)

```java
// {기능}ModalSubmit.java
// block_id별 값 추출 메서드
// private_metadata: "userId|userName" 형식
// 유효성 검증 메서드 (isValid...)
```

- block_id 네이밍: `block_{필드명}`
- action_id 네이밍: `action_{필드명}`

## Step 3. ModalBuilder 생성 (ingest/payload/)

```java
// {기능}ModalBuilder.java
// callback_id: "{기능}_submit" ← SlackFacade 라우팅에 사용
// private_metadata: "userId|userName"
```

## Step 4. Facade 생성 (ingest/facade/)

```java
// {기능}Facade.java
// 듀얼 생성자 (독립 + SlackApiService 주입)
public {기능}Facade() { ... }
public {기능}Facade(SlackApiService shared) { ... }

// handleCommand(body): 모달 열기
// handleModalSubmit(body): 처리 + 응답
// handleBlockAction(body): 드롭다운 등 인터랙션 (필요 시)
```

### 응답 패턴

- 성공/실패: `HttpResponse.modalResult(success, message, title)`
- 필드 에러: `HttpResponse.modalError(blockId, message)` — **block_id 사용 (action_id 아님!)**
- SQS 위임 후: `HttpResponse.ok("")`

## Step 5. SlackFacade 라우팅 등록

`ingest/facade/SlackFacade.java`에서:
1. 슬래시 커맨드 → handleCommand 분기 추가
2. callback_id → handleModalSubmit/handleBlockAction 분기 추가
3. 병렬 초기화에 새 Facade 추가 (CompletableFuture)

## Step 6. SQS 위임 (3초 초과 시)

worker 처리가 필요한 경우:
1. `WorkerMessageService`에 send 메서드 추가
2. worker/dto/sqs/에 메시지 DTO 추가
3. `WorkerHandler`에 messageType 분기 추가
4. worker/facade/에 처리 Facade 생성

## Step 7. 3초 제한 대응 판단

| 처리 시간 | 방법 |
|----------|------|
| < 3초 확실 | 직접 처리 → modalResult |
| > 3초 확실 | SQS 위임 + join() → ok("") |
| 불확실 | join(2500) → 타임아웃 무관 응답 |

## Step 8. 문서 동기화

- [ ] CLAUDE.md: Slack 슬래시 커맨드 테이블에 추가
- [ ] ingest/CLAUDE.md: Facade 목록 업데이트
- [ ] worker/CLAUDE.md: Facade/Service 목록 업데이트 (SQS 위임 시)
- [ ] .claude/rules/ingest.md: 라우팅 테이블 업데이트
- [ ] .claude/rules/env-vars.md: 새 환경변수 추가 (있으면)
