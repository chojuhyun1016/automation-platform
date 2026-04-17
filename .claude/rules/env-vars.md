---
paths:
  - ingest/**
  - worker/**
  - scheduler/**
  - groupware/**
---

# 환경변수 참조

새 환경변수를 추가하거나 기존 환경변수를 사용할 때 이 목록을 참고할 것.

## 공통

| 환경변수 | 모듈 | 필수 | 용도 |
|---------|------|------|------|
| `SLACK_BOT_TOKEN` | 전체 | 필수 | Slack Bot 토큰 (EnvTokenProvider) |
| `JIRA_EMAIL` | clients | 필수 | Jira Basic Auth 이메일 |
| `JIRA_API_TOKEN` | clients | 필수 | Jira Basic Auth 토큰 |
| `CONFIG_BUCKET` | 전체 | 필수 | S3 설정 파일 버킷 |

## ingest

| 환경변수 | 필수 | 용도 |
|---------|------|------|
| `SLACK_SIGNING_SECRET` | 필수 | Slack 요청 서명 검증 |
| `SQS_QUEUE_URL` | 필수 | Worker SQS 큐 URL |
| `TICKET_CALENDAR_ID` | 필수 | 현재티켓 캘린더 ID |
| `CONFIG_KEY` | 선택 | S3 설정 파일 키 (기본: config.json) — 점심카드 calendar_id 포함 |
| `GOOGLE_CALENDAR_CREDENTIALS_BUCKET` | 필수 | Google 자격증명 S3 버킷 |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | 선택 | Google 자격증명 S3 키 (기본: google-credentials.json) |
| `SCHEDULE_MAPPING_TABLE` | 선택 | DynamoDB 일정 매핑 테이블 |
| `TEAM_MEMBERS_KEY` | 선택 | 팀원 정보 S3 키 (기본: team-members.json) |
| `JIRA_BASE_URL` | 선택 | Jira 기본 URL (기본: https://riman-it.atlassian.net) |
| `GROUPWARE_CREDENTIALS_SECRET_NAME` | 선택 | Secrets Manager 시크릿명 (기본: GROUPWARE_CREDENTIALS) |
| `KMS_KEY_ID` | 선택 | KMS 암호화 키 ID |

## worker

| 환경변수 | 필수 | 용도 |
|---------|------|------|
| `CONFIG_KEY` | 필수 | S3 설정 파일 키 |
| `DYNAMODB_TABLE` | 필수 | DedupeService 중복 제거 테이블 |
| `GOOGLE_CALENDAR_CREDENTIALS_BUCKET` | 필수 | Google 자격증명 S3 버킷 |
| `GOOGLE_CALENDAR_CREDENTIALS_KEY` | 선택 | Google 자격증명 S3 키 |
| `CALENDAR_MAPPING_TABLE` | 선택 | Jira-Calendar DynamoDB 매핑 테이블 |
| `SCHEDULE_MAPPING_TABLE` | 선택 | 일정 DynamoDB 매핑 테이블 |
| `TEAM_MEMBERS_KEY` | 선택 | 팀원 정보 S3 키 |
| `GROUPWARE_SQS_QUEUE_URL` | 선택 | 그룹웨어 SQS 큐 URL (미설정 시 비활성화) |

## scheduler

| 환경변수 | 필수 | 용도 |
|---------|------|------|
| `ANTHROPIC_API_KEY` | 선택 | AI 요약 (미설정 시 기본 포맷터) |
| `SCHEDULE_MAPPING_TABLE` | 선택 | 일정 수집 (미설정 시 일정 섹션 미포함) |
| `CONFLUENCE_BASE_URL` | 선택 | Confluence 연동 |
| `CONFLUENCE_SPACE_KEY` | 선택 | Confluence 스페이스 |
| `CALENDAR_MAPPING_TABLE` | 선택 | DynamoDB 매핑 |

## groupware

| 환경변수 | 필수 | 용도 |
|---------|------|------|
| `ECS_CLUSTER_ARN` | 필수 | ECS 클러스터 |
| `ECS_TASK_DEFINITION_ARN` | 필수 | ECS 작업 정의 |
| `ECS_SUBNET_ID` | 필수 | ECS 서브넷 |
| `ECS_SECURITY_GROUP_ID` | 필수 | ECS 보안 그룹 |
| `GROUPWARE_CONFIG_KEY` | 필수 | 그룹웨어 설정 S3 키 (기본: groupware-config.json) |
| `GROUPWARE_CREDENTIALS_SECRET` | 필수 | Secrets Manager 시크릿명 |
| `SLACK_BOT_TOKEN_SECRET_NAME` | 필수 | Slack Bot 토큰 Secrets Manager 시크릿명 |

## 모니터링

| 환경변수 | 모듈 | 필수 | 용도 |
|---------|------|------|------|
| `SENTRY_DSN` | 전체 | 선택 | Sentry 에러 모니터링 DSN (미설정 시 비활성) |
| `SENTRY_ENVIRONMENT` | 전체 | 선택 | Sentry 환경 태그 (기본: production) |
| `SENTRY_RELEASE` | 전체 | 선택 | Sentry 릴리스 태그 (기본: automation-platform@1.0.0) |
| `MONITORING_SLACK_CHANNEL` | worker (DLQ) | 필수* | DLQ 알림 / 쿼타 경고 대상 Slack 채널 ID (*DlqAlertHandler에서 필수) |

## 규칙

- 선택적 환경변수 미설정 시: 로그 남기고 해당 기능 비활성화 (예외 throw 금지)
- 필수 환경변수 미설정 시: `ConfigException` throw
- 새 환경변수 추가 시 이 파일과 해당 모듈 Makefile/Lambda 설정도 함께 갱신할 것
