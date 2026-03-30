---
paths:
  - worker/**/Calendar*
  - worker/**/JiraIssue*
  - worker/**/JiraCalendar*
  - worker/**/Absence*
  - worker/**/RemoteWork*
  - scheduler/**/collect/**
  - scheduler/**/CalendarTicketParser*
  - ingest/**/CurrentTicket*
---

# Google Calendar 데이터 모델 참조

## Jira 티켓 → 캘린더 이벤트 매핑

Jira 티켓은 캘린더에 **종일 이벤트**로 저장된다.

| 캘린더 필드 | 값 | 비고 |
|------------|---|------|
| `event.start.date` | Jira duedate | 캘린더 표시 날짜 = 마감일 |
| `event.end.date` | duedate + 1일 | Google 종일 이벤트 **exclusive end** 규칙 |
| `event.summary` | `[Jira] CCE-1234 (담당자)` | 이슈키 + 담당자명 |
| `event.description` | 구조화된 텍스트 | 아래 포맷 참조 |
| `event.transparency` | `"transparent"` | 일정 충돌 표시 안 함 |

## extendedProperties.private 키

| 키 | 값 예시 | 용도 |
|---|--------|------|
| `jiraIssueKey` | `"CCE-2339"` | 이슈 식별자 (DynamoDB 매핑 키) |
| `assigneeName` | `"홍길동"` | 최종 담당자명 |
| `isTeamMember` | `"true"` | 팀원 여부 |
| `jiraStartDate` | `"2026-02-10"` | Jira 시작일 (yyyy-MM-dd, null 가능) |

## description 포맷

```
Jira Ticket: CCE-2339
Title: 티켓 제목

Assignee: 홍길동
Priority: High
Status: In Progress
Project: CCE
Start Date: 2026-02-10
Due Date: 2026-02-15

View in Jira:
https://riman-it.atlassian.net/browse/CCE-2339
```

## startDate 정규화 로직 (CalendarService.normalizeStartDate)

티켓 생성/업데이트 시 자동 적용:
- startDate가 null/blank → **오늘 날짜**로 대체
- startDate > dueDate → **dueDate**로 보정
- 정규화된 값은 extendedProperties `jiraStartDate`에 저장

## 부재/재택 이벤트

| 유형 | summary 형식 | 날짜 모델 |
|------|-------------|----------|
| 재택 | `재택(홍길동)` | 종일 이벤트 (1일 단위) |
| 연차 | `연차(홍길동)` | 종일 이벤트 (날짜 범위) |
| 반차 | `오전 반차(김철수)` | 종일 이벤트 (단일일) |

- 1인 1이벤트 모델 — 동일 날짜 + 동일 유형 + 동일 이름 이벤트가 이미 있으면 멱등 처리
- cancel 시 summary 정확 매칭으로 이벤트 검색 후 삭제

## 이벤트 검색 전략

### Jira 이벤트
1. DynamoDB `JiraCalendarMappingService` — PK 직접 조회 (빠름)
2. Fallback: `extendedProperties.private.jiraIssueKey`로 Calendar API 검색
3. Fallback 성공 시 DynamoDB에 자동 등록

### 부재/재택 이벤트
- 날짜 범위 + searchQuery(absenceType)로 Calendar API 검색
- 결과에서 summary 정확 매칭으로 필터링

### 티켓 조회 (CurrentTicketFacade, Scheduler)
- Calendar API `searchQuery`는 결과 누락 가능
- **전체 이벤트를 fetch한 후 Java에서 필터링**할 것

## 파싱 규칙 (CalendarTicketParser)

- 이슈키 추출: `\[Jira\]\s+([A-Z]+-\d+)` 또는 `\[([A-Z]+-\d+)\]`
- 담당자 추출: 제목 마지막 `(...)` 에서 쉼표로 분리
- 상태 감지: description `Status: ` 라인 → `JiraStatusCode.fromString()`
- 시작일 파싱: extendedProperties `jiraStartDate` 또는 description `Start Date: `
