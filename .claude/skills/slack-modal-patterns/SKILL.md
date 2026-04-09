---
name: slack-modal-patterns
description: Slack Modal 구현 패턴. callback_id, block_id, response_action, 모달 빌더, 3초 제한 대응.
---

# Slack Modal 구현 패턴

## 새 슬래시 커맨드 추가 시 체크리스트

1. **ModalBuilder** 생성 (ingest/payload/)
   - callback_id: `{기능}_submit` (SlackFacade 라우팅에 사용)
   - block_id: `block_{필드명}` (modalError에서 참조)
   - action_id: `action_{필드명}`

2. **ModalSubmit DTO** 생성 (ingest/dto/slack/)
   - private_metadata: `"userId|userName"` 형식
   - 블록 값 추출 메서드

3. **Facade** 생성 (ingest/facade/)
   - 듀얼 생성자 (독립 + SlackApiService 주입)
   - handleCommand(): 모달 열기
   - handleModalSubmit(): 처리 + 응답

4. **SlackFacade** 라우팅 등록
   - callback_id → 해당 Facade 연결

## 응답 패턴

```java
// 성공/실패 결과 화면
HttpResponse.modalResult(true, "완료 메시지", "결과")

// 필드 에러 (block_id 필수, action_id 아님!)
HttpResponse.modalError("block_start_date", "날짜를 선택해주세요")

// 빈 200 (SQS 위임 후)
HttpResponse.ok("")
```

## 3초 제한 대응

| 시간 | 방법 |
|------|------|
| < 3초 | 직접 처리 후 modalResult 반환 |
| > 3초 | SQS 위임 + join() → ok("") 반환 |
| 불확실 | join(2500) → 타임아웃 여부 무관 응답 |

## Block Actions 주의

- HTTP 응답으로는 모달 업데이트 불가
- `views.update` API 직접 호출 후 200 반환
