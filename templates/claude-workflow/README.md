# Claude 워크플로우 템플릿

Java/Gradle 프로젝트를 위한 Claude Code 개발 워크플로우 패키지.
GitHub Issues 기반 체계적 개발 프로세스를 즉시 적용할 수 있다.

## 포함 내용

| 구분 | 파일 | 설명 |
|------|------|------|
| commands | `quick-fix.md` | 이슈 없이 즉석 분석+수정 |
| commands | `resolve-conflict.md` | Git rebase 충돌 해결 |
| commands | `resolve-issue.md` | 이슈 기반 구현+수정+커밋 |
| commands | `create-issue.md` | Phase → GitHub 이슈 생성 |
| commands | `feature-breakdown.md` | 기능 → Phase 분해 |
| commands | `submit-pr.md` | Rebase + PR 생성 |
| rules | `agents.md` | 에이전트 호출 가이드 |
| skills | `tdd-workflow/` | TDD 절차 (JUnit 5 + Mockito) |
| scripts | `create-worktree.sh` | 워크트리 생성 + Claude 실행 |
| scripts | `cleanup-worktrees.sh` | merge 완료 워크트리 정리 |
| scripts | `check-docs-update.sh` | 코드 변경 시 문서 업데이트 안내 |
| docs | `WORKFLOW.md` | 개발 워크플로우 전체 흐름 |
| docs | `PROJECT_SETUP_GUIDE.md` | 프로젝트별 커스터마이징 가이드 |

## 설치

```bash
cd /path/to/your-project
bash /path/to/templates/claude-workflow/install.sh
```

## 설치 후 할 일

1. `CLAUDE.md` 작성 (프로젝트 개요, 빌드 커맨드, 코딩 컨벤션)
2. `SPEC.md` 생성 (`touch SPEC.md`)
3. `PROJECT_SETUP_GUIDE.md` 참고하여 커스터마이징
4. `.claude/settings.json` 훅 설정 (PROJECT_SETUP_GUIDE.md 참고)

## 워크플로우

```
/feature-breakdown → /create-issue → /resolve-issue → /submit-pr
                                                       /quick-fix (즉석 수정)
```

상세: `WORKFLOW.md` 참조.

## 전제 조건

- Git + GitHub (gh CLI)
- Java 17+ / Gradle (또는 Maven)
- Claude Code
