# scheduler 모듈

EventBridge 트리거로 일일/주간/월간 보고서를 생성하여 Slack DM 또는 Confluence에 전달한다.

## 패키지 구조

```
com.riman.automation.scheduler
├── handler/      SchedulerHandler (Map<String, Object> → String)
├── facade/       DailyReportFacade, WeeklyReportFacade, MonthlyReportFacade
├── dto/
│   ├── report/   DailyReportData (AbsenceItem, TicketItem, PageLinkItem, ScheduleItem),
│   │             WeeklyReportData (WeeklyTicketItem),
│   │             MonthlyReportData (MonthlyTicketItem)
│   └── s3/       DailyReportConfig, WeeklyReportConfig, MonthlyReportConfig,
│                 TeamMember, AnnouncementItem, MemberReportPreference,
│                 ProjectGroup, ArchiveConfig
├── service/
│   ├── collect/  DailyCalendarTicketCollector, DailyJiraTicketCollector,
│   │             DailyAbsenceCollector, DailyScheduleCollector,
│   │             WeeklyCalendarTicketCollector, MonthlyCalendarTicketCollector
│   ├── format/   DailyReportFormatter, WeeklyReportFormatter, MonthlyReportFormatter
│   ├── report/   DailyReportService, WeeklyReportService, MonthlyReportService
│   ├── excel/    WeeklyExcelGenerator, MonthlyExcelGenerator
│   ├── load/     ReportRulesService, TeamMemberService
│   ├── ReportArchiveService (보고서 S3 아카이빙)
│   ├── util/     CalendarTicketParser
│   └── tool/     CalendarStartDateFixer (1회용)
```

## 보고서 선택

`event.report_type` (case-insensitive)로 분기. 기본값: daily.

| 보고서 | time 포맷 | 미입력 시 | 채널 | 첨부 |
|--------|----------|----------|------|------|
| daily | yyyy-MM-dd | KST 오늘 | Slack DM (팀원별) | 없음 |
| weekly | yyyy-MM-dd | 전주 | Confluence + Slack | Excel |
| monthly | yyyy-MM | 전월 | Confluence + Slack | Excel |

## 정적 초기화

`static {}` 블록에서 모든 장수명 객체 생성:
- **항상**: S3Client, SlackClient, JiraClient, GoogleCalendarClient
- **선택적**: ConfluenceClient, AnthropicClient, ScheduleCollector

### 선택적 기능

| 기능 | 환경변수 | 미설정 시 |
|------|---------|----------|
| AI 요약 | `ANTHROPIC_API_KEY` | 기본 포맷터 |
| 일정 수집 | `SCHEDULE_MAPPING_TABLE` | 일정 섹션 미포함 |
| Confluence | `CONFLUENCE_BASE_URL` + `CONFLUENCE_SPACE_KEY` | 비활성화 |

누락된 선택적 환경변수에 예외 throw 금지.

## 데이터 파이프라인

```
Load (S3 설정, 팀원) → Collect (Calendar, Jira, DynamoDB) → Format (Slack/HTML) → Report (전송)
```

## Collector

| 수집기 | 소스 | 특이사항 |
|--------|------|---------|
| DailyCalendarTicketCollector | Calendar + Jira | 담당자명 매칭, Jira fallback |
| DailyJiraTicketCollector | Jira JQL | 미완료 상태, 금주 due date |
| DailyAbsenceCollector | Calendar (2개 병합) | 이벤트 ID 중복 제거 |
| DailyScheduleCollector | DynamoDB + Calendar | eventId 교차 매칭 |
| WeeklyCalendarTicketCollector | Calendar (2회 쿼리) | done(주간) + in-progress(분기) |
| MonthlyCalendarTicketCollector | Calendar (2회 쿼리) | done(월간) + in-progress(분기) |

## Confluence 페이지 계층

```
Weekly: 実績報告 > {year}年週間 > Q{q} > {month}月 > W{week} {team} 実績
Monthly: 実績報告 > {year}年月間 > Q{q} > {month}月 - {team} 実績
```

## CalendarTicketParser 파싱 규칙

- 이슈키: `\[Jira\]\s+([A-Z]+-\d+)` 또는 `\[([A-Z]+-\d+)\]`
- 담당자: 제목 마지막 `(...)` → 쉼표 분리
- 상태: description `Status: ` 라인 → JiraStatusCode
- 시작일: extendedProperties `jiraStartDate` 또는 description `Start Date:`

## Formatter & ReportService

| Formatter | 출력 | 용도 |
|-----------|------|------|
| DailyReportFormatter | Slack mrkdwn | 팀원별 DM (코드 블록/인사말 금지) |
| WeeklyReportFormatter | Confluence Storage HTML | 완료/진행중/이슈별 담당자 그룹핑 |
| MonthlyReportFormatter | Confluence Storage HTML | 월간 동일 구조 |

| ReportService | AI 사용 | 비활성화 시 |
|--------------|---------|------------|
| DailyReportService | AnthropicClient + 규칙 파일 | DailyReportFormatter 원본 사용 |
| WeeklyReportService | — | Confluence 페이지 생성/수정 + Excel 첨부 |
| MonthlyReportService | — | 동일 |

## 1회용 도구

- `CalendarStartDateFixer`: 2026/1~6월 Jira 이벤트 Start Date / Due Date 보정 (CLI)
