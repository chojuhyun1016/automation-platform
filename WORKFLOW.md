# 개발 워크플로우

## 전체 흐름

```
/feature-breakdown [요구사항]
  │  Phase 분해 → SPEC.md 기록
  ▼
/create-issue Phase N1
  │  GitHub 이슈 #12 생성 → SPEC.md에 (#12) 역기록
  ▼
source scripts/create-worktree.sh 12
  │  git worktree + branch issue-12 생성 → Claude 자동 실행
  ▼
/resolve-issue 12
  │  이슈 분석 → 구현 → 리뷰 → PR 생성
  ▼
PR merge → 워크트리 정리
```

## 번호 체계

**Phase 번호와 GitHub 이슈 번호는 다르다.**

| 단계 | 번호 | 예시 |
|------|------|------|
| `/feature-breakdown` | Phase 번호 (내부 계획) | Phase N1, N2, N3 |
| `/create-issue Phase N1` | GitHub 이슈 번호 (자동 부여) | #12 |
| `create-worktree.sh` | GitHub 이슈 번호 사용 | `12` |
| `/resolve-issue` | GitHub 이슈 번호 사용 | `12` |

`/create-issue`가 Phase → GitHub 이슈로 변환하며, SPEC.md에 매핑을 기록한다:
`## Phase N1: 유닛 테스트` → `## Phase N1: 유닛 테스트 (#12)`

## 병렬 작업

병렬 가능한 Phase는 터미널을 나눠 동시 진행:

```bash
# 터미널 1
source scripts/create-worktree.sh 12
# Claude: /resolve-issue 12

# 터미널 2
source scripts/create-worktree.sh 13
# Claude: /resolve-issue 13
```

## 충돌 예방

### Phase 분해 시 파일 경계로 분리

```
BAD:  Phase N1 → CalendarService + AbsenceFacade
      Phase N2 → CalendarService + ScheduleFacade   ← CalendarService 충돌

GOOD: Phase N1 → AbsenceFacade + AbsenceService
      Phase N2 → ScheduleFacade + ScheduleService   ← 파일 겹침 없음
```

### 병렬 가능 판단 기준

| 조건 | 판단 |
|------|------|
| 수정 파일 겹침 없음 | **병렬 가능** |
| common/clients 수정 포함 | **직렬** — 먼저 merge 후 다음 시작 |
| SPEC.md만 겹침 | **병렬 가능** — PR 본문에 기록, main에서 일괄 반영 |

### PR 생성 전 rebase 필수

```bash
# 워크트리에서 PR 생성 전
git fetch origin
git rebase origin/main
```

### 충돌 발생 시 해결

```bash
git fetch origin
git rebase origin/main
# 충돌 파일 수동 해결 후
git add <충돌파일>
git rebase --continue
git push --force-with-lease
```

## 워크트리 정리

### 일괄 정리 (권장)

merge 완료된 워크트리를 한 번에 정리:

```bash
cd /Users/r00365/Work/workspace/automation-platform
bash scripts/cleanup-worktrees.sh
```

- merged PR의 워크트리 + 브랜치 자동 삭제
- 미merge 워크트리는 스킵 + 목록 표시

### 개별 정리

```bash
git worktree remove ../worktree/issue-12
git worktree prune
```

## 커맨드 목록

| 커맨드 | 용도 | 인자 |
|--------|------|------|
| `/feature-breakdown` | 기능 → Phase 분해 → SPEC.md | 요구사항 텍스트 |
| `/create-issue` | Phase → GitHub 이슈 생성 | `Phase N1` 또는 자유 텍스트 |
| `/resolve-issue` | 이슈 분석 → 구현 → PR | GitHub 이슈 번호 |
| `/resolve-conflict` | rebase + 충돌 자동 해결 | 대상 브랜치 (기본: origin/main) |

## 관련 파일

| 파일 | 역할 |
|------|------|
| `SPEC.md` | Phase 기록, 진행 상태 추적 |
| `CLAUDE.md` | 빌드/코딩 컨벤션 (커맨드가 참조) |
| `scripts/create-worktree.sh` | 워크트리 생성 + Claude 실행 |
| `scripts/cleanup-worktrees.sh` | merge 완료된 워크트리 일괄 정리 |
| `scripts/check-docs-update.sh` | 세션 종료 시 문서 갱신 안내 (Stop Hook) |
| `.claude/commands/` | 커맨드 정의 (4개) |
| `.claude/rules/` | 파일 경로 매칭 시 자동 로딩 규칙 (9개) |
