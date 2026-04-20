# 개발 워크플로우

## 전체 흐름

```
/feature-breakdown [요구사항]
  │  Phase 분해 → SPEC.md 기록
  ▼
/create-issue Phase N1
  │  GitHub 이슈 생성 + 브랜치 자동 생성 → SPEC.md 역기록
  ▼
git switch feat/{이슈번호}-{설명} (또는 워크트리)
  ▼
/resolve-issue {이슈번호}
  │  이슈 분석 → 구현 → 리뷰 → 커밋 (push 선택)
  ▼
사용자 확인 (IDE에서 diff, 빌드, 테스트, 배포 검증)
  │
  ├─ 문제 발견 → /resolve-issue {이슈번호} 문제 설명...  ← 수정 루프 (반복 가능)
  │                │  분석 + 수정 + push
  │                ▼
  │            사용자 재확인
  │
  └─ 만족 → /submit-pr {이슈번호}
               │  Rebase + PR 생성 + SPEC.md 체크박스 완료
               ▼
           PR merge → bash scripts/cleanup-worktrees.sh
```

## 브랜치 네이밍

### 형식

```
{타입}/{이슈번호}-{간략설명}
```

### 타입

| 타입 | 용도 | 예시 |
|------|------|------|
| `feat` | 새 기능 | `feat/21-user-authentication` |
| `fix` | 버그 수정 | `fix/7-login-session-expired` |
| `refactor` | 리팩터링 | `refactor/18-extract-payment-service` |
| `chore` | 설정/문서/빌드 | `chore/15-ci-test-enhancement` |
| `hotfix` | 긴급 수정 | `hotfix/22-api-timeout` |

### 규칙

- 설명은 **영어 소문자 + 하이픈** (kebab-case)
- 간결하게 (3~5단어)
- 이슈 번호는 GitHub 이슈 번호 (Phase 번호 아님)

## 번호 체계

**Phase 번호와 GitHub 이슈 번호는 다르다.**

| 단계 | 번호 | 예시 |
|------|------|------|
| `/feature-breakdown` | Phase 번호 (내부 계획) | Phase N1, N2, N8 |
| `/create-issue Phase N8` | GitHub 이슈 번호 (자동 부여) | #21 |
| `create-worktree.sh` | 타입 + 이슈번호 + 설명 | `feat 21 user-authentication` |
| `/resolve-issue` | GitHub 이슈 번호 사용 | `21` |

`/create-issue`가 Phase → GitHub 이슈로 변환하며, SPEC.md에 매핑을 기록한다:
`## Phase N8: 제목` → `## Phase N8: 제목 (#21)`

## 병렬 작업

병렬 가능한 Phase는 터미널을 나눠 동시 진행:

```bash
# 터미널 1
source scripts/create-worktree.sh feat 21 user-authentication
# Claude: /resolve-issue 21

# 터미널 2
source scripts/create-worktree.sh feat 14 payment-integration
# Claude: /resolve-issue 14
```

## 충돌 예방

### Phase 분해 시 파일 경계로 분리

```
BAD:  Phase N1 → UserService + AuthService
      Phase N2 → UserService + PaymentService   ← UserService 충돌

GOOD: Phase N1 → AuthService + AuthRepository
      Phase N2 → PaymentService + PaymentRepository   ← 파일 겹침 없음
```

### 병렬 가능 판단 기준

| 조건 | 판단 |
|------|------|
| 수정 파일 겹침 없음 | **병렬 가능** |
| 공통 모듈 수정 포함 | **직렬** — 먼저 merge 후 다음 시작 |
| SPEC.md만 겹침 | **병렬 가능** — PR 본문에 기록, main에서 일괄 반영 |

### PR 생성 전 rebase 필수

```bash
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
bash scripts/cleanup-worktrees.sh
```

### 개별 정리

```bash
git worktree remove ../worktree/{워크트리명}
git worktree prune
```

## 커맨드 목록

| 커맨드 | 용도 | 인자 |
|--------|------|------|
| `/feature-breakdown` | 기능 → Phase 분해 → SPEC.md | 요구사항 텍스트 |
| `/create-issue` | Phase → GitHub 이슈 생성 | `Phase N1` 또는 자유 텍스트 |
| `/resolve-issue` | 이슈 구현 또는 수정 | `62` (초기) 또는 `62 문제설명` (수정) |
| `/submit-pr` | Rebase + PR 생성 + SPEC.md 완료 | GitHub 이슈 번호 |
| `/resolve-conflict` | rebase + 충돌 자동 해결 | 대상 브랜치 (기본: origin/main) |
| `/quick-fix` | 이슈 없이 즉석 분석 + 수정 | 요구사항 텍스트 |
