---
paths:
  - scheduler/**
---

# scheduler 모듈 규칙

## 아키텍처 개요

scheduler는 EventBridge 트리거로 일일/주간/월간 보고서를 생성하여 Slack DM 또는 Confluence에 전달한다.
`RequestHandler<Map<String, Object>, String>`을 구현한다.

## SchedulerHandler 보고서 선택

`event.report_type` (case-insensitive)로 분기:
- `"daily"` → DailyReportFacade
- `"weekly"` → WeeklyReportFacade
- `"monthly"` → MonthlyReportFacade
- 기본값: `"daily"`

### 시간 필드 파싱 규칙

| 보고서 | time 포맷 | 미입력 시 | 파싱 방법 |
|--------|----------|----------|----------|
| daily | `yyyy-MM-dd` | KST 오늘 | 앞 10자 추출 후 파싱 |
| weekly | `yyyy-MM-dd` | KST 오늘 기준 전주 | 앞 10자, 전주 월요일~일요일 계산 |
| monthly | `yyyy-MM` | KST 오늘 기준 전월 | 앞 7자, `-01` 붙여 유효성 검증 |

파싱 실패 시 warn 로그 후 fallback 사용 — 예외를 throw하지 말 것.

## 정적 싱글톤 초기화

`static {}` 블록에서 모든 장수명 객체를 생성한다:
- S3Client, SlackClient, JiraClient, GoogleCalendarClient (항상)
- ConfluenceClient, AnthropicClient, ScheduleCollector (선택적)

### 선택적 기능 활성화 조건

| 기능 | 필요 환경변수 | 미설정 시 |
|------|-------------|----------|
| AI 요약 | `ANTHROPIC_API_KEY` | 기본 포맷터 사용 |
| 일정 수집 | `SCHEDULE_MAPPING_TABLE` | 일정 섹션 미포함 |
| Confluence | `CONFLUENCE_BASE_URL` + `CONFLUENCE_SPACE_KEY` | 주간/월간 보고서 비활성화 |

누락된 선택적 환경변수에 대해 예외를 throw하지 말 것 — 로그 남기고 해당 기능만 비활성화.

## 데이터 파이프라인

```
Load (S3 설정, 팀원) → Collect (Calendar, Jira, DynamoDB) → Format (Slack/HTML) → Report (전송)
```

### Collector 차이점

| 컬렉터 | 데이터 소스 | 특이사항 |
|--------|-----------|---------|
| DailyCalendarTicketCollector | Calendar + Jira | 담당자명으로 팀원 매칭, Jira fallback |
| DailyAbsenceCollector | Calendar (2개 캘린더 병합) | 이벤트 ID 기반 중복 제거 |
| DailyScheduleCollector | DynamoDB + Calendar | eventId 교차 매칭 |
| WeeklyCalendarTicketCollector | Calendar (2회 쿼리) | done(주간) + in-progress(분기), Jira 상태 재검증 |
| MonthlyCalendarTicketCollector | Calendar (2회 쿼리) | done(월간) + in-progress(분기) |

### Weekly/Monthly In-Progress 필터 조건
- status != DONE
- summary에 `[이슈]` 태그 없음 (이슈 필터와 분리)
- startDate <= weekEnd/monthEnd (또는 null)
- 이슈: summary에 `[이슈]` 태그 포함

## 보고서 전달 채널

| 보고서 | 채널 | 첨부 |
|--------|------|------|
| Daily | Slack DM (팀원별 개별) | 없음 |
| Weekly | Confluence 페이지 + Slack 알림 | Excel |
| Monthly | Confluence 페이지 + Slack 알림 | Excel |

## Confluence 페이지 계층

```
Weekly: 実績報告 > {year}年週間 > Q{q} > {month}月 > W{week} {team} 実績
Monthly: 実績報告 > {year}年月間 > Q{q} > {month}月 - {team} 実績
```
- `ensurePage()`로 중간 계층 보장, `upsertPage()`로 최종 페이지 생성/갱신

## CalendarTicketParser 파싱 규칙

- 이벤트 제목 패턴: `[Jira] CCE-2326 (홍길동)` 또는 `[CCE-123] 제목 (홍길동, 김철수)`
- 담당자 추출: 제목 마지막 `(...)` 에서 쉼표 분리
- 상태 감지: description의 `Status: ` 라인 우선, fallback IN_PROGRESS
- 우선순위 이모지: description 파싱

## ReportRulesService

- S3 규칙 파일 (`rules/DAILY_REPORT_RULES.md`, `rules/WEEKLY_REPORT_RULES.md`)
- `ConcurrentHashMap` 캐시 — Lambda warm 재사용
- 파일 누락 시 `ConfigException` throw (fallback 없음 — 규칙은 AI 처리의 핵심 입력)