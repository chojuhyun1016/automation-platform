---
name: add-scheduler-report
description: 새 스케줄러 보고서 유형을 추가하는 단계별 절차. Collector, Formatter, ReportService, EventBridge 설정까지.
---

# 새 스케줄러 보고서 추가 절차

## Step 1. 보고서 유형 결정

- 주기: daily / weekly / monthly / 신규
- 데이터 소스: Calendar, Jira, DynamoDB 중 어떤 조합
- 출력 채널: Slack DM / Confluence / Excel

## Step 2. Collector 생성 (scheduler/service/collect/)

```java
// {유형}{주기}Collector.java
// 데이터 소스에서 수집 → DTO 리스트 반환
```

기존 Collector 참고:
- Calendar 기반: DailyCalendarTicketCollector 패턴
- Jira 기반: DailyJiraTicketCollector 패턴
- DynamoDB 기반: DailyScheduleCollector 패턴

## Step 3. DTO 생성 (scheduler/dto/report/)

수집 데이터의 아이템 구조 정의. 기존: TicketItem, AbsenceItem, ScheduleItem 참고.

## Step 4. Formatter 생성 (scheduler/service/format/)

```java
// {주기}ReportFormatter.java
// Slack mrkdwn (Daily) 또는 Confluence Storage HTML (Weekly/Monthly)
```

- Daily: Slack Block Kit mrkdwn (코드 블록/인사말 금지)
- Weekly/Monthly: Confluence Storage Format HTML + 담당자별 그룹핑

## Step 5. ReportService 생성 (scheduler/service/report/)

```java
// {주기}ReportService.java
// Formatter 출력 → Slack/Confluence 전송
```

AI 요약 사용 시: AnthropicClient + ReportRulesService 연동.

## Step 6. Facade 생성 (scheduler/facade/)

```java
// {주기}ReportFacade.java
// 파이프라인: Load → Collect → Format → Report
```

## Step 7. SchedulerHandler 분기 추가

`scheduler/handler/SchedulerHandler.java`에서:
- `event.report_type` 분기에 새 유형 추가
- static 초기화 블록에 새 Collector/Client 추가

## Step 8. Excel 생성 (Weekly/Monthly)

`scheduler/service/excel/`에 ExcelGenerator 추가. Apache POI 사용.

## Step 9. 설정 파일

- `config/scheduler-config.json`: 새 보고서 섹션 추가
- S3 규칙 파일 (`config/rules/`): AI 프롬프트 규칙 작성 (AI 요약 사용 시)

## Step 10. EventBridge 설정

AWS 콘솔 또는 IaC:
- EventBridge 스케줄 규칙 생성 (cron 표현식)
- 대상: scheduler Lambda 함수
- 입력: `{"report_type": "신규유형", "time": ""}`

## Step 11. 문서 동기화

- [ ] CLAUDE.md: 해당 없음 (scheduler 내부)
- [ ] scheduler/CLAUDE.md: Collector/Formatter/Service 목록 업데이트
- [ ] .claude/rules/scheduler.md: Collector 테이블, 보고서 채널 테이블 업데이트
- [ ] SPEC.md: Phase 기록 (있으면)
