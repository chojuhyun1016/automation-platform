---
name: add-environment-variable
description: 새 환경변수 추가 절차. 필수/선택 판단, Lambda 설정, 코드 반영, 문서 동기화.
---

# 환경변수 추가 절차

## Step 1. 필수 vs 선택 판단

| 조건 | 판정 | 미설정 시 동작 |
|------|------|--------------|
| 없으면 기능 자체 불가 | **필수** | ConfigException throw |
| 없으면 해당 기능만 비활성화 | **선택** | 로그 남기고 기능 스킵 |

이 프로젝트 원칙: **선택적 환경변수 미설정 시 예외 throw 금지.**

## Step 2. 네이밍

- 형식: `UPPER_SNAKE_CASE`
- 예시: `SCHEDULE_MAPPING_TABLE`, `ANTHROPIC_API_KEY`, `ECS_CLUSTER_ARN`

## Step 3. 코드에서 사용

```java
// 필수
String value = System.getenv("NEW_VAR");
if (value == null || value.isBlank()) {
    throw new ConfigException("NEW_VAR 환경변수가 설정되지 않았습니다");
}

// 선택
String value = System.getenv("NEW_VAR");
if (value == null || value.isBlank()) {
    log.info("NEW_VAR 미설정 — 해당 기능 비활성화");
    return; // 또는 null 반환
}
```

## Step 4. Lambda 콘솔 설정

AWS 콘솔 → Lambda → 함수 선택 → 구성 → 환경변수:
- 키: `NEW_VAR`
- 값: 실제 값

## Step 5. Makefile/build.sh 확인

환경변수가 빌드/배포 시 필요하면 Makefile에도 반영. 런타임 전용이면 불필요.

## Step 6. 문서 동기화

- [ ] `.claude/rules/env-vars.md`: 해당 모듈 테이블에 추가 (필수/선택 명시)
- [ ] 해당 모듈 CLAUDE.md: 환경변수 사용처 설명 (필요 시)
- [ ] `config/README.md`: S3 설정 관련이면 업데이트
- [ ] `PROJECT_SETUP_GUIDE.md`: 팀 설정 가이드 관련이면 업데이트
