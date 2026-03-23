# Config 디렉토리

자동화 플랫폼의 모든 런타임 설정 파일을 관리하는 디렉토리입니다. S3에 업로드되어 각 Lambda/Fargate 모듈이 실행 시 로드합니다.

## 디렉토리 구조

```
config/
├── config.json                # Worker/Ingest 모듈 설정 (Jira 라우팅, 캘린더, 재택/부재)
├── scheduler-config.json      # Scheduler 모듈 설정 (일일/주간/월간 보고서)
├── team-members.json          # 팀원 정보 (이름, Slack ID, Jira ID, 역할)
├── groupware-config.json      # Groupware 모듈 설정 (EKP URL, 결재 규칙)
├── announcements.json         # 팀 공지 목록 (일일 보고서에 표시)
├── google-credentials.json    # Google 서비스 계정 인증키 (Calendar API)
└── rules/                     # AI 보고서 규칙 (Claude 시스템 프롬프트)
    ├── DAILY_REPORT_RULES.md  # 일일 보고서 다듬기 규칙
    └── WEEKLY_REPORT_RULES.md # 주간 보고서 다듬기 규칙
```

## S3 업로드

```bash
# 전체 설정 업로드
aws s3 sync config/ s3://automation-platform-747461205838/ \
  --exclude "README.md" \
  --exclude ".DS_Store"

# 개별 파일 업로드
aws s3 cp config/config.json s3://automation-platform-747461205838/config.json
aws s3 cp config/announcements.json s3://automation-platform-747461205838/announcements.json
```

## 설정 파일별 사용 모듈

| 파일 | 사용 모듈 | 환경변수 (S3 키) |
|------|----------|----------------|
| `config.json` | worker, ingest | `CONFIG_KEY` |
| `scheduler-config.json` | scheduler | `SCHEDULER_CONFIG_KEY` |
| `team-members.json` | worker, scheduler, ingest | `TEAM_MEMBERS_KEY` |
| `groupware-config.json` | groupware | `GROUPWARE_CONFIG_KEY` |
| `announcements.json` | scheduler | `dailyReport.announcements_key` |
| `google-credentials.json` | worker, scheduler, ingest | `GOOGLE_CALENDAR_CREDENTIALS_KEY` |
| `rules/*.md` | scheduler | S3 경로: `rules/DAILY_REPORT_RULES.md` |

---

## 1. config.json

Worker/Ingest 모듈의 핵심 설정으로, Jira 프로젝트별 라우팅 규칙과 재택/부재 캘린더를 정의합니다.

### 구조

```json
{
  "version": "1.1",
  "routing": {
    "<PROJECT_KEY>": {
      "slack_channel_id": "Slack 채널 ID",
      "slack_bot_token_secret": "Secrets Manager 시크릿명",
      "notification_enabled": true,
      "send_to_channel": false,
      "send_to_individuals": true,
      "calendar_enabled": true,
      "calendar_id": "Google Calendar ID"
    }
  },
  "defaultConfig": { ... },
  "remoteWork": { ... },
  "absence": { ... }
}
```

### 전체 예시

```json
{
  "version": "1.1",
  "routing": {
    "CCE": {
      "slack_channel_id": "C09DAQAABS5",
      "slack_bot_token_secret": "automation-slack-bot-token",
      "notification_enabled": true,
      "send_to_channel": false,
      "send_to_individuals": true,
      "calendar_enabled": true,
      "calendar_id": "abc123def456@group.calendar.google.com"
    },
    "RBO": {
      "slack_channel_id": "C09DAQAABS5",
      "slack_bot_token_secret": "automation-slack-bot-token",
      "notification_enabled": true,
      "send_to_channel": false,
      "send_to_individuals": true,
      "calendar_enabled": true,
      "calendar_id": "abc123def456@group.calendar.google.com"
    }
  },
  "defaultConfig": {
    "slack_channel_id": "C09DAQAABS5",
    "slack_bot_token_secret": "automation-slack-bot-token",
    "notification_enabled": true,
    "send_to_channel": false,
    "send_to_individuals": true,
    "calendar_enabled": false,
    "calendar_id": "primary"
  },
  "remoteWork": {
    "calendar_id": "abc123def456@group.calendar.google.com",
    "notification_enabled": false,
    "reminder_days_before": 0
  },
  "absence": {
    "calendar_id": "abc123def456@group.calendar.google.com",
    "notification_enabled": false
  }
}
```

### 필드 설명

#### routing.\<PROJECT_KEY\>

Jira 프로젝트 키(예: CCE, RBO, ABO)별 라우팅 설정입니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `slack_channel_id` | String | Slack 알림 대상 채널 ID |
| `slack_bot_token_secret` | String | AWS Secrets Manager 시크릿명 (Slack Bot Token) |
| `notification_enabled` | Boolean | Slack 알림 활성화 여부 |
| `send_to_channel` | Boolean | `true`: 채널에 메시지 전송 |
| `send_to_individuals` | Boolean | `true`: 담당자 개인 DM 전송 |
| `calendar_enabled` | Boolean | Google Calendar 동기화 활성화 여부 |
| `calendar_id` | String | Google Calendar ID (이벤트 생성/수정 대상) |

#### defaultConfig

`routing`에 해당 프로젝트 키가 없을 때 사용하는 폴백 설정입니다. 필드 구조는 `routing` 항목과 동일합니다.

#### remoteWork

| 필드 | 타입 | 설명 |
|------|------|------|
| `calendar_id` | String | 재택근무 이벤트를 기록할 Google Calendar ID |
| `notification_enabled` | Boolean | 재택 등록 시 알림 여부 |
| `reminder_days_before` | Integer | 리마인더 발송 일수 (0 = 비활성) |

#### absence

| 필드 | 타입 | 설명 |
|------|------|------|
| `calendar_id` | String | 부재(연차/반차/병가) 이벤트를 기록할 Google Calendar ID |
| `notification_enabled` | Boolean | 부재 등록 시 알림 여부 |

---

## 2. scheduler-config.json

Scheduler 모듈의 보고서 설정으로, 일일/주간/월간 보고서의 활성화 여부와 데이터 소스를 정의합니다.

### 구조

```json
{
  "version": "1.1",
  "dailyReport": { ... },
  "weeklyReport": { ... },
  "monthlyReport": { ... }
}
```

### 전체 예시

```json
{
  "version": "1.1",
  "dailyReport": {
    "enabled": true,
    "report_channel_id": "C09DAQAABS5",
    "calendar_id": "abc123def456@group.calendar.google.com",
    "ticket_calendar_id": "abc123def456@group.calendar.google.com",
    "schedule_calendar_id": "abc123def456@group.calendar.google.com",
    "jira_project_keys": ["CCE", "RBO", "ABO", "GADMIN", "GER", "KEEN"],
    "links": [
      { "title": "컨플루언스", "url": "https://your-domain.atlassian.net/wiki/spaces/IT/overview" },
      { "title": "체크리스트", "url": "https://your-domain.atlassian.net/wiki/spaces/IT/pages/12345" },
      { "title": "주간 보고", "url": "https://your-domain.atlassian.net/wiki/spaces/IT/pages/67890" }
    ],
    "announcements_key": "announcements.json"
  },
  "weeklyReport": {
    "enabled": true,
    "team_name": "보상코어 개발팀",
    "confluence_base_url": "https://your-domain.atlassian.net",
    "confluence_space_key": "IT",
    "confluence_parent_page_id": "2337538054",
    "ticket_calendar_id": "abc123def456@group.calendar.google.com"
  },
  "monthlyReport": {
    "enabled": true,
    "team_name": "보상코어 개발팀",
    "confluence_base_url": "https://your-domain.atlassian.net",
    "confluence_space_key": "IT",
    "confluence_parent_page_id": "2337538054",
    "ticket_calendar_id": "abc123def456@group.calendar.google.com"
  }
}
```

### 필드 설명

#### dailyReport

| 필드 | 타입 | 설명 |
|------|------|------|
| `enabled` | Boolean | 일일 보고서 활성화 여부 |
| `report_channel_id` | String | 보고서 전송 Slack 채널 ID |
| `calendar_id` | String | 부재/재택 이벤트 조회 캘린더 |
| `ticket_calendar_id` | String | 티켓 이벤트 조회 캘린더 |
| `schedule_calendar_id` | String | `/일정등록`으로 등록한 일정 조회 캘린더 |
| `jira_project_keys` | String[] | 대상 Jira 프로젝트 키 목록 |
| `links` | Object[] | 보고서 하단 주요 링크 (`title` + `url`) |
| `announcements_key` | String | S3 공지 파일 키 (announcements.json) |

#### weeklyReport / monthlyReport

| 필드 | 타입 | 설명 |
|------|------|------|
| `enabled` | Boolean | 보고서 활성화 여부 |
| `team_name` | String | Confluence 페이지 제목에 포함될 팀명 |
| `confluence_base_url` | String | Confluence Base URL |
| `confluence_space_key` | String | Confluence Space Key |
| `confluence_parent_page_id` | String | 실적보고 루트 페이지 ID |
| `ticket_calendar_id` | String | 완료/진행중/이슈 티켓 조회 캘린더 |

---

## 3. team-members.json

팀원 정보를 관리하며, 모든 모듈에서 Slack ID ↔ 이름 ↔ Jira ID 매핑에 사용합니다.

### 구조

```json
{
  "version": "1.1",
  "updated_at": "ISO 8601 날짜",
  "members": [ ... ],
  "bot": { ... },
  "channels": { ... },
  "metadata": { ... }
}
```

### 전체 예시

```json
{
  "version": "1.1",
  "updated_at": "2026-02-18T13:30:00+09:00",
  "members": [
    {
      "name": "조주현",
      "name_en": "Ju Hyun Cho",
      "email": "juhyun.cho@riman.com",
      "jira_account_id": "712020:408dbc01-e94f-4034-8bbf-4a72d17d8f6d",
      "slack_user_id": "U0627755JP7",
      "active": true,
      "team": "CCE",
      "role": "Manager"
    },
    {
      "name": "김진욱",
      "name_en": "Jinwook Kim",
      "email": "jinwook.kim@riman.com",
      "jira_account_id": "712020:75fde3ae-50b2-4a37-97f7-6523230e64b6",
      "slack_user_id": "U07SMLJ1THB",
      "active": true,
      "team": "CCE",
      "role": "Engineer"
    }
  ],
  "bot": {
    "slack_user_id": "U0AGE6WJNT0",
    "name": "Compensation-Core-Engineering-Bot",
    "description": "CCE Team Bot (DM, Google Calendar, 재택, 공지)"
  },
  "channels": {
    "compensation-core-engineering": {
      "channel_id": "C09DAQAABS5",
      "enabled": true,
      "description": "CCE 팀 채널 (개인 DM 우선, 채널 알림 비활성화)",
      "send_to_channel": false,
      "send_to_individuals": true
    }
  },
  "metadata": {
    "total_members": 7,
    "active_members": 7,
    "teams": ["CCE"],
    "last_sync_date": "2026-02-18"
  }
}
```

### 필드 설명

#### members[]

| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | String | 한글 이름 (캘린더 이벤트 매칭에 사용) |
| `name_en` | String | 영문 이름 |
| `email` | String | 회사 이메일 |
| `jira_account_id` | String | Jira Cloud 계정 ID (Atlassian Account ID) |
| `slack_user_id` | String | Slack 사용자 ID |
| `active` | Boolean | 활성 여부 (`false`면 보고서/알림에서 제외) |
| `team` | String | 소속 팀 코드 (예: `CCE`) |
| `role` | String | 역할 (`Manager` 또는 `Engineer`) |

#### bot

| 필드 | 타입 | 설명 |
|------|------|------|
| `slack_user_id` | String | Bot 사용자 ID |
| `name` | String | Bot 표시 이름 |
| `description` | String | Bot 역할 설명 |

#### channels

| 필드 | 타입 | 설명 |
|------|------|------|
| `channel_id` | String | Slack 채널 ID |
| `enabled` | Boolean | 채널 활성화 여부 |
| `send_to_channel` | Boolean | 채널 직접 전송 여부 |
| `send_to_individuals` | Boolean | 개인 DM 전송 여부 |

#### metadata

| 필드 | 타입 | 설명 |
|------|------|------|
| `total_members` | Integer | 전체 팀원 수 |
| `active_members` | Integer | 활성 팀원 수 |
| `teams` | String[] | 팀 코드 목록 |
| `last_sync_date` | String | 마지막 동기화 일자 |

### role에 따른 동작 차이

| 동작 | Manager | Engineer |
|------|---------|----------|
| 일일 보고서 | 본인 티켓 + 팀원 총괄 섹션 | 본인 티켓만 |
| 그룹웨어 결재자 | `approval_rules`에서 Manager용 결재자 적용 | Manager가 결재자 |

---

## 4. groupware-config.json

Groupware(EKP) 부재 자동 신청 시 사용하는 설정으로, Fargate 태스크(groupware-bot)에 전달됩니다.

### 전체 예시

```json
{
  "version": "1.0",
  "groupware": {
    "base_url": "https://gw.riman.com",
    "login_url": "https://gw.riman.com/ekp/view/login/userLogin",
    "enabled": true,
    "screenshot_bucket": "riman-groupware-screenshots",
    "screenshot_prefix": "groupware-screenshots/",
    "timeout_seconds": 120
  },
  "approval_rules": {
    "CCE": {
      "Engineer": {
        "approver_name": "조주현",
        "approver_search_keyword": "조주현"
      },
      "Manager": {
        "approver_name": "박성현",
        "approver_search_keyword": "박성현"
      }
    }
  }
}
```

### 필드 설명

#### groupware

| 필드 | 타입 | 설명 |
|------|------|------|
| `base_url` | String | 그룹웨어 기본 URL |
| `login_url` | String | 로그인 페이지 URL |
| `enabled` | Boolean | 그룹웨어 자동 신청 활성화 여부 |
| `screenshot_bucket` | String | 디버깅 스크린샷 저장 S3 버킷 |
| `screenshot_prefix` | String | 스크린샷 S3 경로 접두사 |
| `timeout_seconds` | Integer | Playwright 자동화 전체 타임아웃 (초) |

#### approval_rules.\<TEAM\>.\<ROLE\>

팀 + 역할 조합으로 결재자를 결정합니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `approver_name` | String | 결재자 이름 (로그용) |
| `approver_search_keyword` | String | 그룹웨어 결재선에서 검색할 키워드 |

### 결재 규칙 예시

```
CCE 팀 Engineer → 결재자: 조주현 (Manager)
CCE 팀 Manager → 결재자: 박성현 (상위 관리자)
```

---

## 5. announcements.json

일일 보고서의 팀 공지 섹션에 표시되는 항목입니다. `start_date` ~ `end_date` 범위 내에서만 표시됩니다.

### 구조

```json
[
  {
    "message": "공지 내용 텍스트",
    "url": "관련 링크 (없으면 빈 문자열)",
    "type": "강조 타입",
    "start_date": "표시 시작일 (yyyy/MM/dd)",
    "end_date": "표시 종료일 (yyyy/MM/dd)"
  }
]
```

### 전체 예시

```json
[
  {
    "message": "칠레(3/18), 스페인,아일랜드(3/25), 페루,콜롬비아(4/15)",
    "url": "",
    "type": "bold",
    "start_date": "2026/03/04",
    "end_date": "2026/04/16"
  },
  {
    "message": "[수당] P->SM 승급자 M적용 (4/1)",
    "url": "https://your-domain.atlassian.net/browse/CCE-2254",
    "type": "",
    "start_date": "2026/03/04",
    "end_date": "2026/04/02"
  },
  {
    "message": "GRIT/KR/TW 주문 CDC 반영 (4/2)",
    "url": "",
    "type": "red",
    "start_date": "2026/03/16",
    "end_date": "2026/04/03"
  }
]
```

### 필드 설명

| 필드 | 타입 | 설명 |
|------|------|------|
| `message` | String | 공지 내용 텍스트 |
| `url` | String | 관련 링크 URL (빈 문자열이면 링크 없음) |
| `type` | String | 강조 타입 (아래 표 참조) |
| `start_date` | String | 표시 시작일 (`yyyy/MM/dd`) |
| `end_date` | String | 표시 종료일 (`yyyy/MM/dd`, 이 날짜 포함) |

### type 값

| 값 | Slack 렌더링 | 용도 |
|----|------------|------|
| `""` (빈 문자열) | 일반 텍스트 | 일반 공지 |
| `"bold"` | `*굵은 텍스트*` | 중요 공지 |
| `"red"` | 강조 표시 | 긴급/경고성 공지 |

### 기간 필터링

보고서 생성 시 `baseDate`(오늘 기준)가 `start_date` ~ `end_date` 범위에 포함되는 항목만 표시됩니다. 기간이 지난 공지는 자동으로 숨겨지므로 삭제하지 않아도 됩니다.

---

## 6. google-credentials.json

Google Calendar API 인증에 사용하는 서비스 계정 키 파일입니다.

### 구조

```json
{
  "type": "service_account",
  "project_id": "프로젝트 ID",
  "private_key_id": "키 ID",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
  "client_email": "서비스계정@프로젝트.iam.gserviceaccount.com",
  "client_id": "클라이언트 숫자 ID",
  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
  "token_uri": "https://oauth2.googleapis.com/token",
  "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
  "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/...",
  "universe_domain": "googleapis.com"
}
```

### 주의사항

- Google Cloud Console에서 서비스 계정 생성 후 JSON 키를 다운로드하여 사용
- `CalendarScopes.CALENDAR` 스코프 필요
- 대상 캘린더에 서비스 계정의 `client_email`을 편집 권한으로 공유해야 함
- **이 파일은 민감 정보이므로 Git에 커밋하지 않아야 함** (`.gitignore` 권장)

---

## 7. rules/ (AI 보고서 규칙)

Claude API로 보고서를 자연어 다듬기할 때 시스템 프롬프트로 사용되는 마크다운 파일입니다. S3에 업로드되어 코드 배포 없이 규칙을 수정할 수 있습니다.

### DAILY_REPORT_RULES.md

일일 보고서 다듬기 규칙으로, Claude에 시스템 프롬프트로 전달됩니다.

**주요 규칙:**

| 항목 | 규칙 |
|------|------|
| 출력 형식 | Slack mrkdwn 텍스트만 (코드 블록/인사말 금지) |
| 섹션 순서 | 헤더 → 공지 → 부재&재택 → 티켓 → 링크 (변경 불가) |
| 데이터 보존 | 날짜, 이름, 이슈키, 우선순위, 상태 수치 절대 변경 불가 |
| 허용 범위 | 티켓 summary 어색한 표현 다듬기, 공지 조사 수정 |

**티켓 기한 색상 규칙:**

| 기호 | 조건 | 의미 |
|------|------|------|
| 🔴 | `duedate < 오늘` | 기한 만료 |
| 🔵 | `duedate ≤ 오늘+3` | 3일 이내 |
| ⚫ | 그 외 | 정상 |

**우선순위 이모지:**

| 우선순위 | 이모지 |
|---------|-------|
| Highest | 🔴 |
| High | 🟠 |
| Medium | 🟡 |
| Low | 🔵 |
| Lowest | ⚫ |

**보고서 출력 형태:**

```
📊 일일 팀 보고서  |  M/D(요일)   금주[/차주]

📢 팀 공지
• [공지 내용]

🏠 부재 & 재택
🔴 *[이름]* 🏠 재택 (*M/D(요일)*)      ← 오늘
• [이름] 🌴 연차 (M/D(요일))             ← 이후

🎫 티켓 현황
👤 *[담당자]*
[색깔] [우선순위] <url|이슈키> [제목] [상태이모지] _(기한)_

🔗 주요 페이지
• <url|제목>
```

### WEEKLY_REPORT_RULES.md

주간 보고서 다듬기 규칙입니다.

**보고서 출력 형태:**

```
📋 주간 팀 보고서  |  M/D(요일) ~ M/D(요일)   N주차

📢 팀 공지
• [공지 내용]

✅ 이번 주 완료
👤 *[담당자]*
• <url|이슈키> [제목] — [완료일]

🗓 다음 주 예정
👤 *[담당자]*
• <url|이슈키> [제목] _(기한: M/D)_

🏠 다음 주 부재 & 재택
• [이름] [상태이모지] [상태] (M/D(요일))

🔗 주요 페이지
• <url|제목>
```

### 규칙 수정 방법

코드 배포 없이 S3 파일만 수정하면 즉시 반영됩니다:

```bash
# 규칙 수정 후 업로드
aws s3 cp config/rules/DAILY_REPORT_RULES.md \
  s3://automation-platform-747461205838/rules/DAILY_REPORT_RULES.md
```

---

## 설정 변경 시 주의사항

| 변경 대상 | 반영 방법 | 비고 |
|----------|----------|------|
| `config.json` | S3 업로드 후 Worker Lambda 재시작 또는 5분 대기 (캐시 TTL) | 캐시 만료 후 자동 반영 |
| `scheduler-config.json` | S3 업로드 후 다음 스케줄 실행 시 반영 | Lambda cold start마다 로드 |
| `team-members.json` | S3 업로드 후 Lambda 재시작 또는 cold start 대기 | 메모리 캐시, warm 상태에서 유지 |
| `groupware-config.json` | S3 업로드 후 다음 Fargate 태스크 실행 시 반영 | 태스크마다 새로 로드 |
| `announcements.json` | S3 업로드 후 다음 일일 보고서 생성 시 반영 | 매 실행마다 로드 |
| `rules/*.md` | S3 업로드 후 즉시 반영 | AI 호출 시마다 로드 |
| `google-credentials.json` | S3 업로드 후 Lambda 재시작 | cold start 시 1회 로드 |
