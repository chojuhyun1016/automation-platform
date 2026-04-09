
# ═══════════════════════════════════════════════════════════
# 파일 4: .claude/commands/resolve-conflict.md
# ═══════════════════════════════════════════════════════════


# Resolve Conflict

현재 브랜치를 origin/main 기준으로 rebase하고, 충돌이 있으면 해결한다.

**대상 브랜치**: $ARGUMENTS (미입력 시 `origin/main`)

---

## 사용 방법

```
/resolve-conflict                ← origin/main 기준 rebase (기본)
/resolve-conflict origin/develop ← 특정 브랜치 기준
```

## 사용 시점

### 시점 1: `/resolve-issue` PR 생성 전 (자동 연동)
`/resolve-issue`의 9단계에서 rebase 충돌 시 이 프로세스가 실행된다.
**같은 워크트리 안에서, PR 생성 전에 실행.** 워크트리가 닫힌 후가 아님.

### 시점 2: 다른 워크트리에서 독립 실행
```
터미널 1: issue-12 PR merge 완료
터미널 2: issue-13 작업 중 → /resolve-conflict 실행하여 main 최신화
```
다른 PR이 먼저 merge되어 현재 브랜치가 outdated일 때 사용.

---

## 프로세스

### 1. 현재 상태 확인

```bash
git status
git log --oneline -5
```

작업 중 변경사항이 있으면 먼저 커밋하거나 stash할 것을 안내해라.

### 2. 최신화 + Rebase

```bash
git fetch origin
git rebase ${TARGET_BRANCH:-origin/main}
```

### 3-A. 충돌 없음

```
✅ 최신화 완료. 현재 브랜치가 origin/main 기반으로 업데이트되었습니다.
```

### 3-B. 충돌 발생

#### a. 충돌 파일 목록 표시

```bash
git diff --name-only --diff-filter=U
```

충돌 파일 목록과 각 파일의 충돌 위치를 보고해라.

#### b. 충돌 분석

각 충돌 파일에 대해:
- **ours** (현재 브랜치): 이 브랜치에서 변경한 내용
- **theirs** (main): merge된 다른 PR의 변경 내용
- **충돌 원인**: 같은 줄 수정 / 파일 삭제 vs 수정 / 등

#### c. 자동 해결 시도

- CLAUDE.md, SPEC.md 등 문서: 양쪽 변경 모두 반영 (통합)
- 같은 함수의 다른 부분 수정: 양쪽 모두 반영
- 같은 줄 수정: 코드 맥락을 분석하여 올바른 버전 선택
- **판단 불가 시**: 사용자에게 ours/theirs 선택 요청

#### d. 해결 후 계속

```bash
git add <해결된 파일>
git rebase --continue
```

rebase가 여러 커밋에 걸쳐 충돌하면 각 단계마다 반복.

#### e. 해결 불가 시

사용자에게 선택지 제시:
1. 수동으로 해결 후 `git add + git rebase --continue`
2. `git rebase --abort` — 원래 상태로 복원

### 4. 완료

```bash
git push --force-with-lease
```

> `--force-with-lease`는 다른 사람이 같은 브랜치에 push하지 않았을 때만 force push.
> 워크트리 브랜치(issue-N)는 개인용이므로 안전.

---

## 원칙

1. **자동 해결 우선** — 명확한 충돌은 Claude가 해결
2. **판단 불가 시 사용자 확인** — 추측하지 말 것
3. **abort 옵션 항상 제공** — 원래 상태 복원 가능
4. **stash 안내** — 미커밋 변경이 있으면 rebase 전 stash 권유
