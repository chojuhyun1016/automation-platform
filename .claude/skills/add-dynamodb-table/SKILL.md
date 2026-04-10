---
name: add-dynamodb-table
description: 새 DynamoDB 테이블 추가 절차. PK/SK 설계, Service 클래스, IAM, 환경변수, 문서 동기화.
---

# DynamoDB 테이블 추가 절차

## Step 1. 테이블 설계

기존 4개 테이블 참고:

| 테이블 | PK | SK | 용도 |
|--------|----|----|------|
| Dedupe | eventId | timestamp | 중복 방지 |
| CalendarMapping | issueKey | calendarId | Jira↔Calendar |
| ScheduleMapping | slackUserId | eventId | 일정↔Calendar |

설계 원칙:
- PK: 조회 빈도 높은 키
- SK: 범위 쿼리 필요 시 사용
- 속성: 최소한으로 (DynamoDB는 스키마리스)

## Step 2. AWS 콘솔에서 테이블 생성

- 테이블명: `{용도}-{환경}` 또는 프로젝트 네이밍 규칙 따름
- 파티션 키 (PK): String
- 정렬 키 (SK): 필요 시 String
- 용량: 온디맨드 (Lambda 워크로드에 적합)

## Step 3. IAM 정책 추가

Lambda 실행 역할에 DynamoDB 권한 추가:
```json
{
  "Effect": "Allow",
  "Action": ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query", "dynamodb:DeleteItem"],
  "Resource": "arn:aws:dynamodb:ap-northeast-2:747461205838:table/{테이블명}"
}
```

## Step 4. 환경변수 추가

Lambda 콘솔에서:
- 환경변수명: `{용도}_TABLE` (UPPER_SNAKE_CASE)
- 값: 테이블명

## Step 5. Service 클래스 생성

```java
// {모듈}/service/{용도}Service.java
// DynamoDbClient는 static 캐싱 (기존 패턴 참조)
// 선택적 기능이면: 환경변수 미설정 시 null 반환 (예외 throw 금지)
```

기존 패턴 참고:
- JiraCalendarMappingService: PK/SK 직접 조회
- DedupeService: prefix key 패턴 (`TYPE#eventId`)
- ScheduleEventMappingService: Query + 범위 조회

## Step 6. Facade에서 Service 주입

WorkerHandler 생성자 DI 체인에 추가:
```java
{용도}Service service = new {용도}Service();
// → Facade 생성자에 주입
```

## Step 7. 테스트

- DynamoDB 로컬 테스트는 미지원 (현재 프로젝트)
- `make build`로 컴파일 검증
- 배포 후 실제 환경에서 검증

## Step 8. 문서 동기화

- [ ] .claude/rules/env-vars.md: 새 환경변수 추가
- [ ] .claude/rules/worker.md (또는 해당 모듈): DynamoDB 테이블 스키마 추가
- [ ] 해당 모듈 CLAUDE.md: Service 클래스 목록 업데이트
