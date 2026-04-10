---
name: debug-lambda-timeout
description: Lambda 타임아웃/성능 문제 진단 절차. CloudWatch 분석, 캐싱 확인, SQS 위임 판단, 병렬 초기화 최적화.
---

# Lambda 타임아웃/성능 문제 진단 절차

## Step 1. 증상 확인

- Slack에서 `{reason}` 미치환 메시지 → 500 응답 (3초 초과)
- CloudWatch에서 `Task timed out` → Lambda 전체 타임아웃
- Slack 재시도 발생 → `X-Slack-Retry-Num` 로그 확인

## Step 2. CloudWatch Logs 분석

```
# 최근 에러 로그 확인
aws logs filter-log-events \
  --log-group-name /aws/lambda/{함수명} \
  --filter-pattern "ERROR" \
  --start-time $(date -d '1 hour ago' +%s000)
```

확인 항목:
- Cold start 여부 (`INIT_START` 로그)
- 각 단계별 소요 시간
- 어떤 외부 API 호출에서 지연

## Step 3. Cold Start vs Warm 판별

| 로그 | 의미 |
|------|------|
| `INIT_START` 있음 | Cold start — static 초기화 실행 |
| `INIT_START` 없음 | Warm — 캐싱된 객체 재사용 |

Cold start가 원인이면 → Step 4 (캐싱 확인)
Warm인데 느리면 → Step 5 (외부 API 지연)

## Step 4. Static Volatile 캐싱 확인

생성 비용 300ms 이상인 객체가 캐싱되어 있는가?

```java
// 확인할 패턴
private static volatile S3Client cachedS3Client;           // ~300ms
private static volatile GoogleCalendarClient cachedClient;  // ~1200ms
private static volatile Map<String, String> cachedMap;      // ~100ms
```

캐싱 안 된 객체 → `static volatile`로 캐싱 추가.

## Step 5. 외부 API 응답 지연 확인

| API | 정상 응답 | 지연 시 |
|-----|----------|---------|
| Google Calendar | ~200ms | ~2000ms (토큰 갱신 시) |
| Jira REST API | ~300ms | ~1500ms (대량 결과) |
| Slack API | ~100ms | ~500ms (rate limit) |
| DynamoDB | ~50ms | ~200ms (cold partition) |
| S3 | ~100ms | ~500ms (대용량 파일) |

## Step 6. 3초 제한 대응 판단

```
현재 총 소요 예상: ___ms

3초 이내 → 직접 처리 유지
3초 초과 → SQS 위임 전환 필요:
  1. Thread + join() 패턴으로 SQS 전송
  2. worker에서 실제 처리
  3. Slack DM으로 결과 비동기 전송
```

## Step 7. 병렬 초기화 최적화 (ingest)

SlackFacade 생성자의 `ExecutorService(5)` 병렬 초기화 확인:
- 새 Facade가 추가됐는데 병렬에 포함 안 됨?
- 순차 초기화로 인해 전체 시간 증가?

```java
// 병렬 초기화 확인
CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> initFacade1(), executor);
CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> initFacade2(), executor);
CompletableFuture.allOf(f1, f2).join();
```

## Step 8. Pre-warm 패턴 적용 검토

모달 열기 → 사용자 조작 시간(1~3초) → 모달 제출 패턴:
- handleCommand()에서 데몬 스레드로 초기화 시작
- handleModalSubmit()에서 join(2500) 대기

## Step 9. 문서 동기화

- [ ] 캐싱 추가 시 → 해당 모듈 CLAUDE.md 업데이트
- [ ] SQS 위임 전환 시 → .claude/rules/ingest.md + worker.md 업데이트
- [ ] 환경변수 추가 시 → .claude/rules/env-vars.md 업데이트
