# Automation Platform

Slack, Jira, Google Calendar, Confluence, 그룹웨어(EKP) 등 사내 시스템을 연동하여 팀 운영을 자동화하는 서버리스 플랫폼입니다.

## 프로젝트 개요

- **Slack 슬래시 커맨드**: `/재택근무`, `/부재등록`, `/계정관리`, `/일정등록`, `/현재티켓` 5개 커맨드 제공
- **Jira 웹훅 처리**: Jira 이슈 변경 이벤트를 수신하여 Google Calendar 동기화 및 Slack DM 알림
- **보고서 자동 생성**: 일일/주간/월간 보고서를 Slack 및 Confluence에 자동 게시
- **그룹웨어 부재 자동 신청**: Slack에서 등록한 부재를 그룹웨어(EKP)에 자동 결재 상신

## 모듈 구조

```
automation-platform/
├── common/           # 공통 모델, 예외, 유틸리티 (기초 라이브러리)
├── clients/          # 외부 API 클라이언트 (Jira, Slack, Google Calendar, Confluence, Anthropic)
├── ingest/           # Lambda 진입점 - Slack/Jira 이벤트 수신 및 SQS 전달
├── worker/           # Lambda - SQS 메시지 소비, 캘린더 동기화, Slack DM 발송
├── scheduler/        # Lambda - EventBridge 트리거, 보고서 자동 생성
├── groupware/        # Lambda - SQS 소비, 그룹웨어 부재 신청 오케스트레이션
├── groupware-bot/    # Python Docker (Fargate) - Playwright 기반 그룹웨어 브라우저 자동화
├── config/           # S3 업로드용 설정 파일 (팀 정보, 스케줄러 설정, AI 규칙 등)
└── img/              # 문서용 이미지
```

### 모듈 의존성

```
common  ←  clients  ←  ingest
                     ←  worker
                     ←  scheduler
                     ←  groupware

groupware-bot (Python, 독립)
```

## 기술 스택

### Java 모듈 (Gradle 멀티모듈)

| 구분 | 기술 |
|------|------|
| **언어** | Java 17 |
| **빌드** | Gradle 8.5, Shadow JAR |
| **런타임** | AWS Lambda (Java 17) |
| **AWS SDK** | AWS SDK v2 (SQS, S3, DynamoDB, Secrets Manager, KMS) |
| **외부 API** | Jira REST API, Slack API, Google Calendar API v3, Confluence REST API, Anthropic API |
| **JSON** | Jackson 2.16.1 |
| **로깅** | SLF4J 2.0.9 |
| **유틸** | Lombok 1.18.30 |

### Python 모듈 (groupware-bot)

| 구분 | 기술 |
|------|------|
| **언어** | Python 3.11 |
| **런타임** | AWS Fargate (Docker) |
| **브라우저** | Playwright (Chromium) |
| **AWS** | boto3 (Secrets Manager, S3) |
| **알림** | Slack Webhook |

### 인프라

| 구분 | 서비스 |
|------|--------|
| **컴퓨팅** | AWS Lambda (Java), AWS Fargate (Docker) |
| **메시징** | Amazon SQS |
| **스토리지** | Amazon S3 (설정 파일, JAR, 스크린샷) |
| **데이터베이스** | Amazon DynamoDB (이벤트 매핑) |
| **보안** | AWS Secrets Manager, AWS KMS |
| **스케줄링** | Amazon EventBridge |
| **컨테이너** | Amazon ECR |
| **리전** | ap-northeast-2 (서울) |

## 빌드 방법

### 사전 요구사항

- Java 17+
- Gradle 8.5+ (또는 Gradle Wrapper 사용)
- Docker (groupware-bot 빌드 시)
- AWS CLI v2 (배포 시)

### 전체 빌드

```bash
# 전체 Java 모듈 빌드 (Shadow JAR)
make build

# 개별 모듈 빌드
make build-ingest
make build-worker
make build-scheduler
make build-groupware

# groupware-bot Docker 이미지 빌드
make build-bot

# 빌드 아티팩트 정리
make clean
```

### 배포

```bash
# 개별 Lambda 배포 (S3 경유)
make deploy-ingest
make deploy-worker
make deploy-scheduler
make deploy-groupware

# groupware-bot Docker 이미지 ECR 푸시
make push-bot

# 전체 배포
make deploy-all
```

배포 프로세스:
1. Shadow JAR 빌드
2. S3 버킷(`automation-platform-{ACCOUNT_ID}`)에 JAR 업로드
3. Lambda 함수 코드 업데이트

## 환경변수 목록

### ingest Lambda

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `SQS_QUEUE_URL` | Worker용 SQS 큐 URL | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |
| `SLACK_SIGNING_SECRET` | Slack 서명 검증 시크릿 | O |
| `GROUPWARE_CREDENTIALS_SECRET` | Secrets Manager 시크릿명 | - |
| `KMS_KEY_ID` | KMS 키 ID (비밀번호 암호화) | - |
| `SCHEDULE_MAPPING_TABLE` | DynamoDB 테이블명 (일정 매핑) | - |
| `TICKET_CALENDAR_ID` | Google Calendar ID (티켓) | - |
| `GOOGLE_CALENDAR_CREDENTIALS_BUCKET` | S3 버킷 (Google 인증키) | - |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | S3 키 (Google 인증키) | - |
| `CONFIG_BUCKET` | S3 버킷 (설정 파일) | - |
| `TEAM_MEMBERS_KEY` | S3 키 (팀 정보 JSON) | - |
| `JIRA_BASE_URL` | Jira Base URL | - |

### worker Lambda

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `CONFIG_BUCKET` | S3 버킷 (설정 파일) | O |
| `CONFIG_KEY` | S3 키 (config.json) | O |
| `TEAM_MEMBERS_KEY` | S3 키 (team-members.json) | O |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | S3 키 (Google 인증키) | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |
| `JIRA_EMAIL` | Jira 계정 이메일 | O |
| `JIRA_API_TOKEN` | Jira API 토큰 | O |
| `JIRA_BASE_URL` | Jira Base URL | O |
| `CALENDAR_MAPPING_TABLE` | DynamoDB 테이블명 (Jira-Calendar 매핑) | O |
| `SCHEDULE_MAPPING_TABLE` | DynamoDB 테이블명 (일정 매핑) | O |
| `GROUPWARE_SQS_QUEUE_URL` | 그룹웨어용 SQS 큐 URL | - |

### scheduler Lambda

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

### groupware Lambda

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `GROUPWARE_CONFIG_BUCKET` | S3 버킷 (그룹웨어 설정) | O |
| `GROUPWARE_CONFIG_KEY` | S3 키 (groupware-config.json) | O |
| `ECS_CLUSTER` | ECS 클러스터 ARN | O |
| `ECS_TASK_DEFINITION` | ECS 태스크 정의 ARN | O |
| `ECS_SUBNETS` | ECS 서브넷 ID (쉼표 구분) | O |
| `ECS_SECURITY_GROUPS` | ECS 보안 그룹 ID (쉼표 구분) | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |

### groupware-bot (Fargate)

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `GROUPWARE_CREDENTIALS_SECRET` | Secrets Manager 시크릿명 | O |
| `GROUPWARE_CONFIG_BUCKET` | S3 버킷 (그룹웨어 설정) | O |
| `GROUPWARE_CONFIG_KEY` | S3 키 (groupware-config.json) | O |
| `SLACK_USER_ID` | 요청 사용자 Slack ID | O |
| `ABSENCE_TYPE` | 부재 유형 | O |
| `START_DATE` | 시작일 (yyyy-MM-dd) | O |
| `END_DATE` | 종료일 (yyyy-MM-dd) | O |
| `REASON` | 부재 사유 | - |
| `SCREENSHOT_BUCKET` | S3 버킷 (스크린샷 저장) | - |