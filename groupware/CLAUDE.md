# groupware 모듈

Java Lambda 오케스트레이터. SQS 메시지를 받아 Python ECS Fargate 태스크를 실행한다.
그룹웨어(EKP) 부재 신청 브라우저 자동화.

## 패키지 구조

```
com.riman.automation.groupware
├── handler/    GroupwareHandler (SQSEvent → Void)
├── facade/     GroupwareAbsenceFacade
├── dto/        GroupwareAbsenceMessage
└── service/    EcsTaskService
```

## 처리 흐름

```
SQS → GroupwareHandler → GroupwareAbsenceFacade
                          ├── apply/cancel 판별 (cancel → Slack 수동 안내)
                          ├── S3 groupware-config.json 로드
                          ├── approval_rules에서 결재자 resolve
                          ├── 태스크 환경변수 구성 (비밀번호 절대 미포함)
                          ├── EcsTaskService → Fargate 실행
                          └── Slack DM "처리 중" 알림
```

## EcsTaskService

- 환경변수: `ECS_CLUSTER_ARN`, `ECS_TASK_DEFINITION_ARN`, `ECS_SUBNET_ID`, `ECS_SECURITY_GROUP_ID`
- 컨테이너명: `"groupware-bot"` (Task Definition 일치 필수)
- `AssignPublicIp.ENABLED` — NAT Gateway 없이 인터넷 접근
- Static EcsClient 캐싱

## 자격증명 보안 원칙

```
Java → Python 전달:
  ✓ GROUPWARE_CREDENTIALS_SECRET (시크릿 이름만)
  ✓ SLACK_BOT_TOKEN_SECRET_NAME (시크릿 이름만)
  ✗ ID/PW, API 토큰 절대 전달 금지
```

Python 태스크가 Secrets Manager에서 직접 조회.
