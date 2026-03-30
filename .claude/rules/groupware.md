---
paths:
  - groupware/**
  - groupware-bot/**
---

# groupware 모듈 규칙

## 아키텍처 개요

Java Lambda 오케스트레이터(groupware)가 SQS 메시지를 받아 Python ECS Fargate 태스크(groupware-bot)를 실행한다.
그룹웨어(EKP) 부재 신청 브라우저 자동화를 수행한다.

## Java 오케스트레이터 (groupware/)

### GroupwareHandler
- `RequestHandler<SQSEvent, Void>` — SQS 배치 처리
- 첫 번째 실패 시 `RuntimeException` throw (SQS 재시도 정책 활용)

### GroupwareAbsenceFacade 처리 흐름
1. apply/cancel 판별 — cancel은 자동화 불가, Slack DM으로 수동 안내
2. S3에서 `groupware-config.json` 로드
3. `approval_rules`에서 팀/역할로 결재자 resolve
4. 태스크 환경변수 구성 — **비밀번호를 절대 포함하지 말 것**
5. EcsTaskService로 Fargate 태스크 실행
6. Slack DM으로 "처리 중" 알림

### EcsTaskService 설정
- 환경변수: `ECS_CLUSTER_ARN`, `ECS_TASK_DEFINITION_ARN`, `ECS_SUBNET_ID`, `ECS_SECURITY_GROUP_ID`
- 컨테이너명: `"groupware-bot"` (Task Definition과 일치 필수)
- `AssignPublicIp.ENABLED` — NAT Gateway 없이 직접 인터넷 접근
- Static EcsClient 캐싱

### 자격증명 보안 원칙
```
Java Lambda에서 Python Task로 전달되는 것:
  ✓ GROUPWARE_CREDENTIALS_SECRET (시크릿 이름만)
  ✓ SLACK_BOT_TOKEN_SECRET_NAME (시크릿 이름만)
  ✗ ID/PW, API 토큰은 절대 전달하지 말 것
```
Python 태스크가 Secrets Manager에서 직접 조회한다.

## Python 봇 (groupware-bot/)

### 기술 스택
- Python 3.11, Playwright (Chromium headless), boto3, cryptography
- asyncio 미사용 (동기 방식)

### main.py 실행 흐름
1. 환경변수 파싱
2. Secrets Manager에서 Slack 토큰 로드
3. Secrets Manager에서 그룹웨어 자격증명 로드 (KMS 복호화)
4. Playwright로 부재 신청 자동화 실행
5. 성공/실패 Slack DM 전송
6. `sys.exit(0)` 또는 `sys.exit(1)`

### KMS 봉투 암호화 (secrets_client.py)
```
저장 포맷: "ENC:base64(encDataKey).base64(IV).base64(ciphertext)"
복호화: KMS decrypt(encDataKey) → AES-256-GCM decrypt(plainKey, IV, ciphertext)
```
- 레거시 평문 비밀번호도 지원 (ENC: 접두사 없는 경우)

### Slack 알림 (slack_notifier.py)
- **urllib만 사용** (requests 라이브러리 의존성 없음)
- `conversations.open` → `chat.postMessage` 패턴

### 부재 유형 분류
- Group A (단일 날짜): 오전 반차, 오후 반차, 오전 반반차, 오후 반반차, 보건 휴가
- Group B (시작~종료): 연차, 병가 등 기타

### Docker 빌드
```dockerfile
FROM mcr.microsoft.com/playwright/python:v1.42.0-jammy
# PYTHONUNBUFFERED=1 (CloudWatch 즉시 로깅)
# linux/amd64 필수 (Fargate x86_64)
```
- `make build-bot` → 빌드, `make push-bot` → ECR 푸시

### 디버깅
- `_shot(page, label)` 유틸리티로 S3에 스크린샷 저장
- 파일명: `{prefix}{label}_{timestamp}.png`