# Groupware 모듈

그룹웨어(EKP) 부재 자동 신청을 오케스트레이션하는 Lambda 모듈입니다.

## 모듈 개요

Worker 모듈이 SQS에 발행한 `groupware_absence` 메시지를 소비하여, AWS Fargate에서 groupware-bot(Python)을 실행시키는 중간 조정자 역할을 합니다.

- **SQS 메시지 소비**: 부재 신청 요청 수신
- **Fargate 태스크 실행**: groupware-bot Docker 컨테이너 기동
- **설정 관리**: S3에서 그룹웨어 설정 로드 (결재자 정보 등)
- **결과 알림**: Slack DM으로 성공/실패 알림

## 패키지 구조

```
com.riman.automation.groupware/
├── handler/
│   └── GroupwareHandler            # Lambda 진입점 (SQS 이벤트)
├── facade/
│   └── GroupwareAbsenceFacade      # 부재 신청 오케스트레이션
├── service/
│   ├── FargateTaskService          # ECS Fargate 태스크 실행 (RunTask API)
│   ├── GroupwareConfigService      # S3에서 groupware-config.json 로드
│   └── SlackNotifyService          # 결과 Slack DM 알림
└── dto/
    ├── GroupwareAbsenceMessage     # SQS 메시지 구조
    └── GroupwareConfig             # 설정 DTO (결재 규칙 포함)
```

## 의존성

### 내부 모듈

| 모듈 | 용도 |
|------|------|
| `common` | 예외 클래스, AbsenceTypeCode |
| `clients` | SlackClient |

### 외부 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `aws-lambda-java-core` / `aws-lambda-java-events` | Lambda 런타임 |
| AWS SDK v2: `ecs`, `s3` | Fargate 실행, 설정 로드 |
| `jackson-databind` | JSON 처리 |
| `slf4j-simple` | 로깅 |

## 주요 환경변수

| 변수명 | 설명 | 필수 |
|--------|------|:----:|
| `GROUPWARE_CONFIG_BUCKET` | S3 버킷 (그룹웨어 설정) | O |
| `GROUPWARE_CONFIG_KEY` | S3 키 (groupware-config.json) | O |
| `ECS_CLUSTER` | ECS 클러스터 ARN | O |
| `ECS_TASK_DEFINITION` | ECS 태스크 정의 ARN | O |
| `ECS_SUBNETS` | 서브넷 ID (쉼표 구분) | O |
| `ECS_SECURITY_GROUPS` | 보안 그룹 ID (쉼표 구분) | O |
| `SLACK_BOT_TOKEN` | Slack Bot Token | O |

## 핵심 흐름

```
SQS 메시지 (groupware_absence)
    ↓
GroupwareHandler → GroupwareAbsenceFacade
    ↓
1. S3에서 groupware-config.json 로드
   ├─ 기본 URL, 타임아웃
   └─ 팀별 결재자 규칙 (Engineer → 조주현, Manager → 박성현)
    ↓
2. 결재자 결정 (team + role 기반)
    ↓
3. Fargate 태스크 실행 (RunTask API)
   ├─ 환경변수로 파라미터 전달
   │   ├─ SLACK_USER_ID, ABSENCE_TYPE
   │   ├─ START_DATE, END_DATE, REASON
   │   └─ GROUPWARE_CREDENTIALS_SECRET
   └─ groupware-bot 컨테이너 실행
    ↓
4. Slack DM 알림
   ├─ 성공: "부재 신청이 완료되었습니다"
   └─ 실패: "부재 신청에 실패했습니다" + 오류 메시지
```

### groupware-config.json 구조

```json
{
  "base_url": "https://gw.riman.com",
  "enabled": true,
  "timeout_seconds": 120,
  "screenshot_bucket": "riman-groupware-screenshots",
  "approval_rules": {
    "CCE": {
      "Engineer": { "approver_keyword": "조주현" },
      "Manager": { "approver_keyword": "박성현" }
    }
  }
}
```
