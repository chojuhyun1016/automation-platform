# Scheduler 모듈

EventBridge 스케줄에 의해 트리거되어 일일/주간/월간 보고서를 자동 생성하는 Lambda 모듈입니다.

## 모듈 개요

정해진 시간에 자동 실행되어 다음 보고서를 생성합니다:

- **일일 보고서**: Slack 채널에 팀 현황 (부재/재택, 티켓 현황, 공지, 일정) 게시
- **주간 보고서**: Confluence 페이지 + 엑셀 첨부 + Slack 알림
- **월간 보고서**: Confluence 페이지 + 엑셀 첨부 + Slack 알림
- **AI 다듬기**: Claude API로 보고서 텍스트를 자연스럽게 다듬기 (실패 시 원본 폴백)

## 패키지 구조

```
com.riman.automation.scheduler/
├── handler/
│   └── SchedulerHandler               # Lambda 진입점 (EventBridge)
├── facade/
│   └── ReportFacade                   # 보고서 종류별 오케스트레이션
├── service/
│   ├── report/                        # 보고서 서비스
│   │   ├── DailyReportService         # AI 다듬기 + Slack 전송
│   │   ├── WeeklyReportService        # Confluence 페이지 계층 생성 + 엑셀 첨부
│   │   └── MonthlyReportService       # 월간 Confluence 페이지 생성
│   ├── collect/                       # 데이터 수집기
│   │   ├── DailyAbsenceCollector      # Google Calendar → 부재/재택 현황
│   │   ├── DailyTicketCollector       # Jira API → 팀원별 활성 티켓
│   │   ├── WeeklyCalendarTicketCollector # Calendar → 주간 완료/진행중 티켓
│   │   └── DailyScheduleCollector     # Calendar → 오늘 등록 일정
│   ├── format/                        # 포맷터
│   │   ├── DailyReportFormatter       # Slack Block Kit mrkdwn 생성
│   │   ├── WeeklyReportFormatter      # Confluence Storage HTML 생성
│   │   └── MonthlyReportFormatter     # 월간 HTML 생성
│   ├── excel/                         # 엑셀 생성
│   │   └── WeeklyExcelGenerator       # Apache POI → .xlsx 바이트 배열
│   ├── load/                          # 설정/규칙 로드
│   │   ├── ConfigLoaderService        # S3에서 scheduler-config.json 로드
│   │   └── ReportRulesService         # S3에서 AI 규칙 마크다운 로드
│   └── util/
│       └── CalendarTicketParser       # 캘린더 이벤트 → 이슈키/담당자/상태 파싱
├── dto/
│   ├── report/                        # 보고서 데이터 VO
│   │   ├── DailyReportData            # 일일: 부재/티켓/일정/링크
│   │   └── WeeklyReportData           # 주간: 완료/진행중/이슈 티켓
│   └── s3/                            # S3 설정 DTO
│       ├── DailyReportConfig          # 일일 보고서 설정
│       ├── TeamMember                 # 팀원 정보
│       └── AnnouncementItem           # 공지 항목
└── resources/
    └── application.yml
```

## 의존성

### 내부 모듈

| 모듈 | 용도 |
|------|------|
| `common` | 코드 Enum, DateTimeUtil, SlackBlockBuilder, 예외 클래스 |
| `clients` | JiraClient, SlackClient, GoogleCalendarClient, ConfluenceClient, AnthropicClient |

### 외부 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `aws-lambda-java-core` / `aws-lambda-java-events` | Lambda 런타임 |
| AWS SDK v2: `s3` | S3 설정 로드 |
| `google-api-services-calendar` v3 | Google Calendar API |
| `apache-poi` / `poi-ooxml` | 엑셀 생성 (.xlsx) |
| `jackson-databind` / `jackson-datatype-jsr310` | JSON 처리 |
| `slf4j-simple` | 로깅 |

## 주요 환경변수

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `CONFIG_BUCKET` | S3 버킷 (설정 파일) | O |
| `SCHEDULER_CONFIG_KEY` | S3 키 (scheduler-config.json) | O |
| `TEAM_MEMBERS_KEY` | S3 키 (team-members.json) | O |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | S3 키 (Google 인증키) | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |
| `JIRA_EMAIL` | Jira 계정 이메일 | O |
| `JIRA_API_TOKEN` | Jira API 토큰 | O |
| `JIRA_BASE_URL` | Jira Base URL | O |
| `CONFLUENCE_BASE_URL` | Confluence Base URL | - |
| `CONFLUENCE_SPACE_KEY` | Confluence Space Key | - |
| `ANTHROPIC_API_KEY` | Claude API 키 (AI 보고서) | - |

## 핵심 흐름

### 일일 보고서

```
EventBridge (매일 오전)
    ↓
SchedulerHandler → ReportFacade.generateDailyReport()
    ↓
1. S3에서 설정 로드 (scheduler-config.json, team-members.json)
2. 데이터 수집 (병렬)
   ├─ DailyAbsenceCollector: Google Calendar → 부재/재택 현황
   ├─ DailyTicketCollector: Jira API → 팀원별 미완료 티켓
   └─ DailyScheduleCollector: Calendar → 오늘 일정
3. DailyReportFormatter: Slack mrkdwn 포맷
4. (선택) DailyReportService: Claude API로 자연어 다듬기
5. Slack 채널 전송
```

### 주간 보고서

```
EventBridge (매주 금요일)
    ↓
1. 티켓 수집 (WeeklyCalendarTicketCollector)
   ├─ 전주 완료: Calendar weekStart~weekEnd, Status=DONE
   └─ 진행중/이슈: Calendar quarterStart~quarterEnd, Status≠DONE
2. Confluence 페이지 계층 생성
   └─ 실적 → {year}년 주간 → Q{q} → {month}월 → W{week} 실적
3. WeeklyReportFormatter: Confluence Storage HTML 생성
4. WeeklyExcelGenerator: 엑셀 생성 + 페이지 첨부
5. Slack 알림 (페이지 링크)
```

### AI 보고서 다듬기 (DailyReportService)

```
DailyReportData
    ↓ DailyReportFormatter.formatAsPlainText()
구조화된 plain text
    ↓ AnthropicClient.complete(rules, text)
Claude가 다듬은 mrkdwn
    ↓ SlackBlockBuilder
최종 Slack Block Kit JSON

※ AI 실패 시 자동으로 원본 포맷 폴백
※ 규칙: S3 rules/DAILY_REPORT_RULES.md (코드 배포 없이 수정 가능)
```
