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

## 리포트별 티켓 수집 조건

### 공통 규칙

#### 데이터 소스

| 리포트 | 캘린더 | Jira API |
|--------|:------:|:--------:|
| 일일 | O | O |
| 주간 | O | - |
| 월간 | O | - |

- 캘린더 이벤트 중 `extendedProperties["jiraIssueKey"]`가 존재하는 이벤트만 Jira 티켓으로 인식
- 이벤트 제목의 담당자명이 팀원 목록에 포함된 이벤트만 수집

#### 프로젝트/카테고리 분류

| 프로젝트 키 | 카테고리 | 비고 |
|------------|---------|------|
| RBO | RBO | |
| ABO | ABO | |
| GADMIN | ABO | ABO로 통합 |
| GER | 회원 | |
| KEEN | 회원 | 회원으로 통합 |
| CCE | summary 태그 기반 | `[주문]`, `[회원]`, `[수당]`, `[포인트]`, `[ABO]`, `[RBO]` |
| CCE (태그 없음) | **제외** | 보고서에 미포함 |
| 기타 프로젝트 | **제외** | 보고서에 미포함 |

카테고리 표시 순서: 주문 → 회원 → 수당 → 포인트 → ABO → RBO

#### DONE 판정 상태값

| 프로젝트 | 완료 상태 |
|---------|----------|
| RBO | `done`, `answered`, `duplicated`, `grit 이관`, `listed`, `reject` |
| ABO | `monitoring in progress` |
| CCE | `완료`, `반려`, `취소` |

#### 마감일 긴급도 (DueDateUrgencyCode)

| 조건 | 코드 | 표시 |
|------|------|------|
| dueDate 지남 | OVERDUE | 🔴 기간만료 |
| 3일 이내 | URGENT | 🔵 3일이내 |
| 3일 초과 | NORMAL | ⚫ 정상 |
| dueDate 없음 | NONE | ⬜ 없음 |

#### startDate / dueDate 보정 (CalendarService.normalizeStartDate)

티켓 생성/업데이트 시 자동 적용:
- startDate가 null → 오늘 날짜로 대체
- startDate > dueDate → dueDate로 보정

---

### 일일 보고서 (DailyReport)

**전달 방식**: Slack 채널 메시지
**트리거**: EventBridge (매일 오전)

#### 수집 범위

| 소스 | 범위 | 비고 |
|------|------|------|
| 캘린더 (금주 티켓) | 이번 주 월~일 | 금요일이면 차주 일요일까지 확장 |
| 캘린더 (연체 티켓) | 과거 6개월 | 미완료 상태인 과거 티켓 포함 |
| Jira API | 미완료 전체 | 아래 JQL 참조 |

#### 캘린더 수집 조건

| 조건 | 설명 |
|------|------|
| 팀원 이벤트만 | 이벤트 제목 `(담당자명)` 이 팀원 목록에 포함 |
| Jira 상태 재검증 | `dueDate < 이번주 월요일 AND status == IN_PROGRESS` 인 티켓 → Jira API로 실제 상태 확인 |

#### Jira API 수집 조건 (JQL)

```
project in (CCE, RBO, ABO, GADMIN, GER, KEEN)
AND statusCategory in ('new', 'indeterminate')
AND issuetype != Epic
AND (duedate <= '{endDate}' OR duedate is EMPTY)
ORDER BY priority ASC, duedate ASC
```

| 조건 | 설명 |
|------|------|
| statusCategory | 'new', 'indeterminate' (미완료만, Done 제외) |
| issuetype != Epic | 에픽 제외 |
| duedate <= endDate | 마감일이 조회 범위 이내이거나 마감일 없는 티켓 |

#### 정렬 순서

캘린더 티켓: 마감일 근접순 → 우선순위순
Jira 티켓: 우선순위순 → 마감일 근접순

---

### 주간 보고서 (WeeklyReport)

**전달 방식**: Confluence 페이지 + 엑셀 첨부 + Slack 알림
**트리거**: EventBridge (매주 금요일)

#### 날짜 범위

| 범위 | 계산 | 예시 (baseDate = 2026-03-25) |
|------|------|------|
| weekStart | 지난주 월요일 | 2026-03-16 |
| weekEnd | 지난주 일요일 | 2026-03-22 |
| quarterStart | 해당 분기 1일 | 2026-01-01 |
| quarterEnd | 해당 분기 마지막일 | 2026-03-31 |

#### 완료 (Done) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | weekStart ~ weekEnd (지난주) |
| status == DONE | 완료 상태만 |
| Jira 상태 재검증 | IN_PROGRESS 티켓을 Jira API로 실제 상태 확인 후 DONE 여부 재판정 |

#### 진행중 (In-Progress) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | quarterStart ~ quarterEnd (분기 전체) |
| status != DONE | 미완료 상태 |
| `[이슈]` 태그 없음 | summary에 `[이슈]` 미포함 |
| startDate <= weekEnd | 시작일이 지난주 일요일 이전 (미래 예정 티켓 제외) |
| startDate == null | **포함** (이전 이벤트 호환) |

#### 이슈 (Issue) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | quarterStart ~ quarterEnd (분기 전체) |
| status != DONE | 미완료 상태 |
| `[이슈]` 태그 있음 | summary에 `[이슈]` 포함 |
| startDate <= weekEnd | 시작일이 지난주 일요일 이전 (미래 예정 이슈 제외) |
| startDate == null | **포함** (이전 이벤트 호환) |

---

### 월간 보고서 (MonthlyReport)

**전달 방식**: Confluence 페이지 + 엑셀 첨부 + Slack 알림
**트리거**: EventBridge (매월 초)

#### 날짜 범위

| 범위 | 계산 | 예시 (대상월 = 2026-02) |
|------|------|------|
| monthStart | 대상월 1일 | 2026-02-01 |
| monthEnd | 대상월 마지막일 | 2026-02-28 |
| quarterStart | 해당 분기 1일 | 2026-01-01 |
| quarterEnd | 해당 분기 마지막일 | 2026-03-31 |

대상월 미지정 시 이전 달 자동 선택.

#### 완료 (Done) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | monthStart ~ monthEnd (대상월) |
| status == DONE | 완료 상태만 |
| Jira 상태 재검증 | IN_PROGRESS 티켓을 Jira API로 실제 상태 확인 후 DONE 여부 재판정 |

#### 진행중 (In-Progress) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | quarterStart ~ quarterEnd (분기 전체) |
| status != DONE | 미완료 상태 |
| `[이슈]` 태그 없음 | summary에 `[이슈]` 미포함 |
| startDate <= monthEnd | 시작일이 대상월 마지막일 이전 (미래 예정 티켓 제외) |
| startDate == null | **포함** (이전 이벤트 호환) |

#### 이슈 (Issue) 티켓

| 조건 | 설명 |
|------|------|
| 캘린더 조회 범위 | quarterStart ~ quarterEnd (분기 전체) |
| status != DONE | 미완료 상태 |
| `[이슈]` 태그 있음 | summary에 `[이슈]` 포함 |
| startDate <= monthEnd | 시작일이 대상월 마지막일 이전 (미래 예정 이슈 제외) |
| startDate == null | **포함** (이전 이벤트 호환) |

---

### 리포트 간 비교 요약

| 항목 | 일일 | 주간 | 월간 |
|------|------|------|------|
| 완료 범위 | - | 지난주 | 대상월 |
| 진행중/이슈 범위 | 금주 + 과거 6개월 | 분기 전체 | 분기 전체 |
| startDate 필터 | - | <= weekEnd | <= monthEnd |
| Jira API 직접 조회 | O | - | - |
| 상태 재검증 | O (과거 연체 티켓) | O (IN_PROGRESS) | O (IN_PROGRESS) |
| `[이슈]` 분리 | - | O | O |
| 전달 채널 | Slack | Confluence + Slack | Confluence + Slack |
