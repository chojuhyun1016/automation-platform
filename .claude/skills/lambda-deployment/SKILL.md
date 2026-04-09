---
name: lambda-deployment
description: Lambda 모듈 빌드 → S3 업로드 → Lambda 함수 업데이트 배포 절차. make 커맨드 기반.
---

# Lambda 배포 절차

## 단일 모듈 배포

```bash
# 1. 빌드
make build-{모듈}    # ingest, worker, scheduler, groupware

# 2. 배포 (S3 업로드 + Lambda 업데이트)
make deploy-{모듈}
```

## 전체 배포

```bash
make deploy-all      # Lambda 4개 + Docker 순차 배포
```

## groupware-bot (Docker)

```bash
make build-bot       # Docker 빌드 (linux/amd64)
make push-bot        # ECR 푸시
```

## 배포 전 확인

1. `make build` — 전체 shadowJar 빌드 성공 확인
2. `config/` 변경이 있으면 S3에도 업로드했는지 확인
3. 환경변수 추가 시 Lambda 콘솔에서 설정 확인

## Lambda 함수명 (Makefile 기준)

| 모듈 | 함수명 |
|------|--------|
| ingest | AutomationWebhookIngest |
| worker | AutomationWebhookWorker |
| scheduler | AutomationScheduler |
| groupware | automation-groupware |

build.sh의 함수명은 참고용. **Makefile이 정본.**
