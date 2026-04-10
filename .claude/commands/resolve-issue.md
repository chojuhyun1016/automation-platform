
# ═══════════════════════════════════════════════════════════
# 파일 3: .claude/commands/resolve-issue.md
# ═══════════════════════════════════════════════════════════


# Resolve Issue

GitHub 이슈를 가져와 분석하고 해결한다.

**이슈 번호**: #$ARGUMENTS

> **⚠️ $ARGUMENTS는 GitHub 이슈 번호이다 (Phase 번호가 아님).**
> Phase N1 ≠ #12. `/create-issue Phase N1` 실행 시 GitHub이 자동 부여한 번호(#12)를 사용한다.
> SPEC.md에 `Phase N1: 유닛 테스트 (#12)` 형태로 매핑이 기록되어 있다.

---

## 사용 방법

```
# 워크트리에서 Claude 실행 후:
/resolve-issue 12      ← GitHub 이슈 #12 (Phase 번호 아님)
/resolve-issue 37      ← GitHub 이슈 #37
```

**전제**: `source scripts/create-worktree.sh {타입} {이슈번호} {설명}` 로 생성된 워크트리에서 실행.
워크트리가 이미 별도 브랜치이므로 브랜치를 생성하지 않는다.

**전체 흐름 (번호 변환 과정)**:
```
/feature-breakdown                            → SPEC.md에 Phase N1, N2 기록
/create-issue Phase N1                        → GitHub 이슈 #12 생성 + SPEC.md 역기록
source scripts/create-worktree.sh feat 12 desc → 브랜치 feat/12-desc + 워크트리 생성
/resolve-issue 12                              → GitHub #12 조회 → SPEC.md Phase 매칭 → 구현
```

---

## 프로세스

### 1. 이슈 가져오기

`gh issue view $ARGUMENTS` 로 이슈 내용(제목, 본문, 라벨, 댓글)을 가져와라.

### 2. SPEC.md 확인

SPEC.md에서 #$ARGUMENTS에 해당하는 Phase가 있는지 확인해라.
- **있으면**: Phase의 작업 항목을 계획 기반으로 사용. **재분해하지 마라.**
- **없으면**: 3단계부터 새로 계획.

### 3. 이슈 분석

- **요약**: 1-2문장
- **유형**: feature / bug / refactor / enhancement / chore
- **영향 범위**: 어떤 파일/모듈에 영향
- **수용 기준**: 해결 완료 조건

### 4. 코드 탐색

**Explore 서브에이전트를 context fork로 실행** (메인 컨텍스트 절약).

- 관련 파일, 모듈, 패턴 파악
- CLAUDE.md 컨벤션 확인

외부 라이브러리 관련 시 **Context7 MCP로 최신 문서 확인.** 추측 금지.

### 5. 해결 계획

**TodoWrite로 구조화해라.**

```markdown
## 해결 계획: #이슈번호 — 제목

### 변경 사항
- [ ] **`파일 경로`** — 변경 내용
  - [ ] 세부 작업

### 검증
- [ ] 빌드 성공 (CLAUDE.md 빌드 커맨드 참조)
- [ ] [수용 기준별 검증]
```

### 6. 사용자 검토

계획을 보여주고 승인 후 구현 진행.

### 7. 구현

- **TDD 적용 기준** (브랜치 타입 확인):
  - `feat` 브랜치: **테스트 먼저 작성 (TDD 필수)** — skills/tdd-workflow 참조
  - `fix` 브랜치: 버그 재현 테스트 작성 **권장** (강제 아님)
  - `refactor` 브랜치: 기본 불필요. **단, 로직 변경 포함 또는 10개+ 파일 수정 시 TDD 권장**
  - `chore`/`hotfix` 브랜치: TDD 불필요
- **이슈 범위만 수정** — 요청하지 않은 리팩터링, 정리, 개선을 포함하지 말 것
- CLAUDE.md 컨벤션을 따라라
- 커밋은 작은 단위로 나눠라
- 검증 항목을 모두 통과시켜라
- **컨텍스트가 쌓이면 `/compact` 실행**

### 8. 자체 리뷰

구현 완료 후 ECC 서브에이전트로 리뷰:
- **code-reviewer** — 전체 리뷰
- 언어별 리뷰어가 있으면 추가 실행 (java-reviewer, typescript-reviewer 등)
- 보안 관련 변경 시 **security-reviewer** 추가

critical/high 이슈 → 수정 후 재검증.

### 9. Rebase + PR 생성

- **PR 생성 전 rebase 필수**:
  ```bash
  git fetch origin
  git rebase origin/main
  ```
  충돌 발생 시 `/resolve-conflict` 프로세스를 실행하여 해결해라.
- `gh pr create --title "[#$ARGUMENTS] 제목" --body "resolves #$ARGUMENTS"`
- PR 본문에 변경 요약 + 검증 결과
- SPEC.md Phase가 있으면 체크박스 완료: `- [x] Phase N 완료`
- `gh issue close $ARGUMENTS` 제안

### 10. 후속 안내

```
1. 메인 프로젝트로: cd <프로젝트 루트>
2. 워크트리 일괄 정리: bash scripts/cleanup-worktrees.sh
3. 다음 이슈: source scripts/create-worktree.sh {타입} {번호} {설명}
```

---

## 원칙

1. **이슈 범위 준수** — 요구하지 않은 작업 포함 금지
2. **최소 변경** — 해결에 필요한 최소한만
3. **SPEC.md 우선** — 기존 Phase가 있으면 따라라
4. **검증 필수** — 수용 기준을 검증 항목에 반영
5. **파일 경로 명시** — 모든 변경 항목에 경로 포함
