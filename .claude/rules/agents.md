# 프로젝트 에이전트

## 프로젝트 에이전트 (.claude/agents/)

| 에이전트 | 용도 | 사용 시점 |
|---------|------|----------|
| planner | 구현 계획 | 복합 기능, 리팩터링 |
| architect | 아키텍처 설계 | 모듈 간 의존성 변경, 새 모듈 추가 |
| code-reviewer | 일반 코드 리뷰 | 코드 수정 후 |
| java-reviewer | Java 특화 리뷰 | Lambda/Facade/Service 변경 시 |
| java-build-resolver | Java 빌드 오류 | `./gradlew compileJava` 실패 시 |
| build-error-resolver | 범용 빌드 오류 | Shadow JAR, Docker 빌드 실패 시 |
| security-reviewer | 보안 분석 | KMS/Secrets Manager/자격증명 코드 변경 시 |

## 즉시 사용 규칙

- 기능 구현 요청 → **planner**
- 코드 수정 완료 → **java-reviewer** (Java), **code-reviewer** (기타)
- 빌드 실패 → **java-build-resolver**
- KMS/Secrets Manager 코드 변경 → **security-reviewer**

## 병렬 실행

독립 작업은 항상 병렬 에이전트로 처리:
- 예: 모듈 A 리뷰 + 모듈 B 리뷰 + 보안 분석 → 3개 병렬
