---
paths:
  - groupware/**
---

# groupware 모듈 규칙 (Java 오케스트레이터)

> Python bot 상세는 `groupware-bot/CLAUDE.md` 참조.

## 아키텍처 개요

Java Lambda 오케스트레이터가 SQS 메시지를 받아 Python ECS Fargate 태스크(groupware-bot)를 실행한다.

## GroupwareHandler
- `RequestHandler<SQSEvent, Void>` — SQS 배치 처리
- 첫 번째 실패 시 `RuntimeException` throw (SQS 재시도 정책 활용)

## GroupwareAbsenceFacade 처리 흐름
1. apply/cancel 판별 — cancel은 자동화 불가, Slack DM으로 수동 안내
2. S3에서 `groupware-config.json` 로드
3. `approval_rules`에서 팀/역할로 결재자 resolve
4. 태스크 환경변수 구성 — **비밀번호를 절대 포함하지 말 것**
5. EcsTaskService로 Fargate 태스크 실행
6. Slack DM으로 "처리 중" 알림

## EcsTaskService 설정
- 환경변수: `ECS_CLUSTER_ARN`, `ECS_TASK_DEFINITION_ARN`, `ECS_SUBNET_ID`, `ECS_SECURITY_GROUP_ID`
- 컨테이너명: `"groupware-bot"` (Task Definition과 일치 필수)
- `AssignPublicIp.ENABLED` — NAT Gateway 없이 직접 인터넷 접근
- Static EcsClient 캐싱

## 자격증명 보안 원칙
```
Java Lambda에서 Python Task로 전달되는 것:
  ✓ GROUPWARE_CREDENTIALS_SECRET (시크릿 이름만)
  ✓ SLACK_BOT_TOKEN_SECRET_NAME (시크릿 이름만)
  ✗ ID/PW, API 토큰은 절대 전달하지 말 것
```
Python 태스크가 Secrets Manager에서 직접 조회한다.