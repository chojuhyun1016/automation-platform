# 프로젝트 설정 가이드

이 템플릿을 설치한 후 프로젝트에 맞게 커스터마이징하는 가이드.

## 1. 필수 전제 조건

| 항목 | 필요 |
|------|------|
| Git 저장소 | O |
| GitHub (gh CLI) | O — 이슈/PR 생성에 사용 |
| Gradle 또는 Maven | O — 빌드/테스트 커맨드 |
| CLAUDE.md | O — 프로젝트별 자체 작성 |

## 2. CLAUDE.md 작성 (프로젝트별)

각 프로젝트에서 직접 작성해야 할 항목:

```markdown
# CLAUDE.md

## 프로젝트 개요
{프로젝트 설명}

## 모듈 구조
{디렉토리 트리}

## 빌드/배포
{빌드 커맨드 — ./gradlew build, mvn package 등}

## 코딩 컨벤션
{패키지 구조, 네이밍, 예외 처리 등}

## 테스트
{테스트 커맨드 — ./gradlew test 등}
```

## 3. SPEC.md 생성

`/feature-breakdown`이 Phase를 기록하는 파일. 빈 파일로 시작:

```bash
touch SPEC.md
```

`/feature-breakdown`이 자동으로 Phase 섹션을 추가한다.

## 4. 커맨드 커스터마이징 포인트

### resolve-issue.md

| 항목 | 기본값 | 변경 시점 |
|------|--------|----------|
| 빌드 커맨드 | `./gradlew :모듈:compileJava` | Maven이면 `mvn compile -pl 모듈` |
| 빌드 에러 에이전트 | `build-error-resolver` | 그대로 사용 가능 |
| 코드 리뷰 에이전트 | `code-reviewer` + `java-reviewer` | 언어 변경 시 `python-reviewer` 등 |

### quick-fix.md

| 항목 | 기본값 | 변경 시점 |
|------|--------|----------|
| 빌드 검증 | CLAUDE.md 빌드 커맨드 참조 | CLAUDE.md에 빌드 커맨드만 적으면 자동 |

### create-issue.md

| 항목 | 기본값 | 변경 시점 |
|------|--------|----------|
| 라벨 | enhancement, bug, refactor, chore | 추가 라벨 필요 시 테이블에 추가 |
| 브랜치 타입 | feat, fix, refactor, chore | 그대로 사용 가능 |

## 5. settings.json 훅 설정 (권장)

프로젝트 `.claude/settings.json`에 추가할 권장 훅:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "if echo \"$CLAUDE_TOOL_INPUT\" | grep -q 'git push.*main'; then echo 'BLOCKED: main 직접 푸시 금지 - PR을 통해 merge' && exit 1; fi"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "if echo \"$CLAUDE_FILE_PATH\" | grep -qE '\\.java$'; then MODULE=$(echo \"$CLAUDE_FILE_PATH\" | grep -oE '(모듈1|모듈2|모듈3)' | head -1) && [ -n \"$MODULE\" ] && ./gradlew :$MODULE:compileJava -q 2>&1 | tail -5; fi"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "./gradlew build -q 2>&1 | tail -10",
            "timeout": 120
          },
          {
            "type": "command",
            "command": "bash scripts/check-docs-update.sh"
          }
        ]
      }
    ]
  }
}
```

`모듈1|모듈2|모듈3` 부분을 프로젝트 실제 모듈명으로 교체.

## 6. 워크플로우 시작

```
# 큰 기능 개발:
/feature-breakdown 사용자 인증 기능 추가

# 단발성 버그/이슈:
/create-issue 로그인 시 세션 만료 처리 버그

# 긴급 수정:
/quick-fix NPE 발생, 로그 첨부...
```
