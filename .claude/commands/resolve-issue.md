

# ═══════════════════════════════════════════════════════════
# 파일 3: .claude/commands/resolve-issue.md
# ═══════════════════════════════════════════════════════════


# Resolve Issue

GitHub 이슈를 가져와 분석하고 해결한다. 구현 + 커밋 + push까지 수행하며, PR 생성은 `/submit-pr`로 분리되어 있다.

**이슈 번호**: #$ARGUMENTS

> **⚠️ $ARGUMENTS는 GitHub 이슈 번호이다 (Phase 번호가 아님).**
> Phase N1 ≠ #12. `/create-issue Phase N1` 실행 시 GitHub이 자동 부여한 번호(#12)를 사용한다.
> SPEC.md에 `Phase N1: 유닛 테스트 (#12)` 형태로 매핑이 기록되어 있다.

---

## 사용 방법

```
/resolve-issue 12      ← 이슈 #12에 해당하는 브랜치에서 실행
```

**전제**: `/create-issue`에서 브랜치가 이미 생성되어 있어야 한다. `git switch` 또는 워크트리로 해당 브랜치에서 실행.

**전체 흐름**:
```
/create-issue Phase N1                          → 이슈 #12 생성 + 브랜치 자동 생성
git switch feat/12-desc (또는 워크트리)          → 브랜치 전환
/resolve-issue 12                               → 구현 + 커밋 + push
  사용자: IDE에서 확인, 테스트, 배포 검증
/submit-pr 12                                   → Rebase + PR 생성 + SPEC.md 완료
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

### 4. 브랜치 확인

현재 브랜치가 이슈에 해당하는 feature/fix 브랜치인지 확인해라:
- **feature/fix 브랜치인 경우**: 그대로 진행
- **main인 경우**: 중단 + 안내
  ```
  ⚠️ main 브랜치에서는 실행할 수 없습니다.
  /create-issue에서 브랜치가 생성되어 있습니다. 전환 후 다시 실행하세요:
    git switch {브랜치명}
  ```
- **기타**: 사용자에게 확인

### 5. 코드 탐색

**Explore 서브에이전트를 병렬로 실행** (메인 컨텍스트 절약):
- 영향 범위가 **1개 모듈**: Explore 1개
- 영향 범위가 **2개+ 모듈**: Explore 2~3개 병렬 (모듈별 분담)
  - 예: Explore A → ingest, Explore B → worker, Explore C → common/clients

각 Explore에서:
- 관련 파일, 모듈, 패턴 파악
- CLAUDE.md 컨벤션 확인

외부 라이브러리 관련 시 **Context7 MCP로 최신 문서 확인.** 추측 금지.

### 6. 해결 계획

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

### 7. 사용자 검토

계획을 보여주고 승인 후 구현 진행.

### 8. 구현

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

### 9. 자체 리뷰

구현 완료 후 ECC 서브에이전트 **병렬 실행**:
- **code-reviewer** + **java-reviewer** → 동시 실행
- 보안 관련 변경 시 **security-reviewer** 도 동시 추가 (최대 3개 병렬)

critical/high 이슈 → 수정 후 재검증.

### 10. 브랜치 push

```bash
git push -u origin {현재 브랜치}
```

### 11. 후속 안내

```
✅ 구현 완료 — 브랜치에 push됨.

다음 단계:
1. IDE에서 변경사항 확인 (diff, 빌드, 테스트)
2. 필요 시 추가 수정 후 커밋 + push
3. 만족하면: /submit-pr $ARGUMENTS
```

---

## 원칙

1. **이슈 범위 준수** — 요구하지 않은 작업 포함 금지
2. **최소 변경** — 해결에 필요한 최소한만
3. **SPEC.md 우선** — 기존 Phase가 있으면 따라라
4. **검증 필수** — 수용 기준을 검증 항목에 반영
5. **파일 경로 명시** — 모든 변경 항목에 경로 포함
6. **PR은 분리** — 이 커맨드는 커밋+push까지만. PR은 `/submit-pr`
