---
paths:
  - worker/**
---

# worker 모듈 규칙

## 아키텍처 개요

worker는 SQS 소비자로서 Jira-Calendar 동기화, 부재/재택/일정 처리를 담당한다.
`RequestHandler<SQSEvent, Void>`를 구현한다.

## WorkerHandler 메시지 디스패치

메시지 타입 결정 순서:
1. SQS MessageAttribute `messageType` 확인
2. 없으면 JSON body의 `messageType` 필드
3. 없으면 기본값 `jira_webhook`

| messageType | Facade | 기능 |
|-------------|--------|------|
| `remote_work` | RemoteWorkFacade | 재택근무 캘린더 등록/취소 |
| `absence` | AbsenceFacade | 부재 캘린더 + 그룹웨어 연동 |
| `schedule` | ScheduleFacade | 일정 캘린더 CRUD |
| (default) | JiraIssueFacade | Jira 웹훅 처리 |

## 생성자 DI 구조

```java
public WorkerHandler() {
    ConfigService configService = new ConfigService();        // S3 설정 로드
    CalendarService calendarService = new CalendarService(configService);
    // ... 공유 인스턴스를 facade에 주입
}
```
- **ConfigService를 한 번만 생성**하여 S3 이중 로딩을 방지할 것
- GroupwareMessageService는 `getInstance()` 싱글톤 사용

## CalendarService 핵심 로직

### Jira 이벤트 처리 흐름
- `processJiraEvent()`: CREATE/UPDATE/DELETE 분기
- CREATE: 기존 이벤트 확인 (DynamoDB hit → UPDATE로 전환하여 중복 방지)
- UPDATE: 담당자 변경 이력 관리 (팀원 → 비팀원 처리)

### DynamoDB 2계층 조회
1. `JiraCalendarMappingService.findMapping(issueKey, calendarId)` — PK 직접 조회
2. Fallback: `findJiraEventByIssueKey()` — extendedProperties 스캔 (pre-DynamoDB 이벤트용)
3. Fallback 성공 시 DynamoDB에 자동 등록 (하위 호환)

### startDate 정규화 (`normalizeStartDate`)
티켓 생성/업데이트 시 자동 적용:
- startDate null/blank → 오늘 날짜로 대체
- startDate > dueDate → dueDate로 보정

## AbsenceFacade 처리 파이프라인

1. JSON → AbsenceMessage 파싱
2. TeamMemberService로 한글 이름 resolve (fallback: msg.getName())
3. DedupeService 중복 확인 (`ABSENCE#` + eventId)
4. 반차 등 단일일 유형은 endDate = startDate 자동 보정
5. endDate < startDate → startDate로 보정
6. AbsenceService로 캘린더 처리 (날짜별 1인 1이벤트 모델)
7. apply인 경우 GroupwareMessageService → GROUPWARE_SQS_QUEUE_URL
8. 캘린더 처리 실패 시 예외를 throw하지 말 것 (DLQ 방지)

## ScheduleFacade

- register: DedupeService → CalendarService.insert → ScheduleEventMappingService.save
- delete: `findMapping(slackUserId, eventId)` 소유권 확인 후 삭제
- DateTime vs 종일: `startDateTime`에 `T`가 포함되면 시간 이벤트, 아니면 종일 이벤트

## DynamoDB 서비스 스키마

### JiraCalendarMappingService
- **테이블**: `CALENDAR_MAPPING_TABLE`
- **PK/SK**: issueKey / calendarId
- **속성**: eventId, assigneeName, createdAt, updatedAt

### ScheduleEventMappingService
- **테이블**: `SCHEDULE_MAPPING_TABLE`
- **PK/SK**: slackUserId / eventId
- **속성**: calendarId, title, startDateTime, endDateTime, slackUserName, koreanName, createdAt

### DedupeService
- **테이블**: `DYNAMODB_TABLE`
- **Jira 중복**: composite key (eventId + timestamp)
- **기능별 중복**: prefix key (`REMOTE#`, `ABSENCE#`, `SCHEDULE#` + eventId)

## ConfigService 캘린더 ID Fallback 체인

```
absence.calendar_id → remoteWork.calendar_id → routing["CCE"].calendar_id → "primary"
```
- 5분 TTL 캐시, 만료 시 자동 갱신

## 이벤트 제목 규칙

| 유형 | summary 형식 |
|------|-------------|
| 재택 | `재택(홍길동)` |
| 부재 | `연차(홍길동)`, `오전 반차(김철수)` |
| 일정 | `[일정] 제목` |
| Jira | `[Jira] CCE-1234 (홍길동)` |