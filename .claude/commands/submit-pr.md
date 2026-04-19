
# Submit PR

구현이 완료된 브랜치에서 Rebase + PR 생성 + SPEC.md 체크박스 완료 처리를 수행한다.
`/resolve-issue`로 구현 + push 후, 사용자가 확인/테스트를 마친 뒤 실행한다.

**이슈 번호**: #$ARGUMENTS

> **⚠️ $ARGUMENTS는 GitHub 이슈 번호이다 (Phase 번호가 아님).**

---

## 사용 방법

```
# resolve-issue 완료 후, 테스트/확인 후:
/submit-pr 12
```

**전제**: 현재 브랜치에 구현이 커밋+push되어 있어야 한다.

**전체 흐름**:
```
/resolve-issue 12    → 구현 + 커밋 + push
  사용자 확인/테스트
/submit-pr 12        → Rebase + PR + SPEC.md 완료 ← 여기
```

---

## 프로세스

### 1. 상태 확인

```bash
git status
git log --oneline origin/main..HEAD
```

- 커밋이 없으면 중단 + 안내: `/resolve-issue $ARGUMENTS` 를 먼저 실행해라
- uncommitted 변경이 있으면: 커밋 여부를 사용자에게 확인

### 2. 이슈 조회

`gh issue view $ARGUMENTS`로 이슈 제목, 라벨을 가져와라.

### 3. Rebase

```bash
git fetch origin
git rebase origin/main
```

충돌 발생 시 `/resolve-conflict` 프로세스를 실행하여 해결해라.

### 4. Push

```bash
git push --force-with-lease
```

### 5. PR 생성

- 기존 PR이 있는지 먼저 확인:
  ```bash
  gh pr list --head "$(git branch --show-current)" --state open --json number --jq '.[0].number'
  ```
- **기존 PR 있으면**: 스킵 (이미 push로 업데이트됨)
- **없으면**: PR 생성
  ```bash
  gh pr create --title "[#$ARGUMENTS] 제목" --body-file /tmp/pr-body.md
  ```
- PR 본문에 변경 요약 + 검증 결과 포함

### 6. SPEC.md Phase 체크박스 완료 처리

SPEC.md에서 #$ARGUMENTS에 해당하는 Phase를 찾아 **모든 체크박스를 완료 처리**:

- 최상위 `- [x] Phase N 완료 (PR #N)` 체크 (PR 번호 기록)
- `### 수정/개선` 내 **모든 세부 체크박스** `[x]` (중첩 항목 포함)
- `### 검증` 내 **모든 체크박스** `[x]`
- 관례는 루트 `CLAUDE.md` "SPEC.md Phase 체크박스 완료 규칙" 섹션 참조
- 누락 시 새 Claude 세션이 잘못된 "미완료" 상태를 신뢰하여 중복 작업 발생

SPEC.md 변경을 커밋 + push:
```bash
git add SPEC.md
git commit -m "docs: SPEC.md Phase N{번호} 체크박스 완료 처리"
git push
```

### 7. 이슈 종료 제안

```
PR이 생성되었습니다: {PR URL}

이슈를 종료할까요?
  gh issue close $ARGUMENTS
```

### 8. 후속 안내

```
1. PR 리뷰 대기 또는 직접 merge
2. merge 후: git switch main && git pull
3. 워크트리 사용 시: bash scripts/cleanup-worktrees.sh
4. 다음 이슈: /resolve-issue {번호} 또는 /create-issue Phase N{다음}
```

---

## 원칙

1. **구현은 하지 않는다** — 코드 수정은 `/resolve-issue` 담당
2. **Rebase 필수** — PR 생성 전 origin/main과 동기화
3. **SPEC.md 완료 필수** — Phase 체크박스를 빠짐없이 처리
4. **기존 PR 중복 방지** — 이미 열린 PR이 있으면 스킵
