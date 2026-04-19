
# ═══════════════════════════════════════════════════════════
# 파일 1: .claude/commands/feature-breakdown.md
# ═══════════════════════════════════════════════════════════


# Feature Breakdown

기능 요구사항을 실행 가능한 Phase 단위로 분해하여 SPEC.md에 기록한다.

**기능 요구사항**: $ARGUMENTS

---

## 사용 방법

```
/feature-breakdown 반복 일정 등록 기능 추가
/feature-breakdown Sprint 변경 이벤트 웹훅 처리
```

완료 후 → `/create-issue Phase N` → GitHub 이슈 #번호 생성 → 브랜치 생성 → `/resolve-issue {번호}` → 확인/테스트 → `/submit-pr {번호}`

> **번호 체계**: Phase 번호(N1, N2)와 GitHub 이슈 번호(#12, #13)는 다르다.
> `/create-issue`가 Phase → GitHub 이슈로 변환하며, SPEC.md에 `Phase N1 (#12)` 형태로 매핑을 기록한다.

---

## 프로세스

### 1. 문제 이해

- **목표**: 요구사항의 최종 상태
- **현재 상태**: CLAUDE.md, SPEC.md, 관련 소스 코드를 읽어라
- **제약 조건**: 기술 스택, 기존 컨벤션, 영향 범위

정보가 부족하면 질문해라. 추측하지 마라.

### 2. 코드 탐색

**Explore 서브에이전트를 context fork로 실행해라** (메인 컨텍스트 절약).

- 변경/영향 파일 목록
- 재사용 가능한 기존 패턴
- CLAUDE.md 컨벤션 확인

외부 라이브러리 관련 시 **Context7 MCP로 최신 문서를 확인해라.** 추측 금지.

### 3. Phase 분해

**planner 에이전트를 실행하여 구현 계획을 수립해라** (복잡 기능, 다모듈 변경, Phase 3개 이상 예상 시).
planner에게 Step 1-2의 탐색 결과 + 요구사항 + 제약조건을 전달하고, Phase 분해 초안을 받아라.
단순 작업(1-2 Phase)은 **Sequential Thinking MCP**로 직접 분해해도 무방.

각 Phase 기준:
- **독립 실행 권장**: 가능하면 이 Phase만으로 빌드 통과
- **독립 불가 시**: SPEC.md에 실행 순서와 주의사항을 명시
- **충분한 문맥**: 다른 세션의 Claude가 이 Phase만 읽고 작업 완수 가능
- **적정 크기**: 하나의 Claude 세션에서 완료 가능한 범위

분해 시 고려:
- **수직 분해**: 설계 → 구현 순서
- **수평 분해**: 병렬 가능한 Phase는 명시
- **의존성**: Phase 간 선행 조건 명확히 기술
- **독립 불가 Phase**: 반드시 순서와 이유를 기술
- **충돌 예방**: 병렬 Phase는 수정 파일이 겹치지 않도록 분리할 것. common/clients 수정은 직렬 처리 권장

### 4. Phase 형식

```markdown
## Phase N: [제목]

- [ ] Phase N 완료

### 오버뷰
[작업 이유 + 변경 내용 1-2문장]

### 메타
- **라벨**: feature / bug / refactor / enhancement / chore
- **우선순위**: high / medium / low
- **병렬 가능**: 예 / 아니오

### 전제조건
- [ ] Phase N-1 완료 (또는 "없음")

### 수정/개선
- [ ] **`파일 경로`** — 변경 내용
    - [ ] 세부 작업

### 검증
- [ ] 빌드 성공 (CLAUDE.md 빌드 커맨드 참조)
- [ ] [기능별 검증 항목]

### 주의사항 (독립 실행 불가 시)
- [이 Phase 단독으로 빌드 불가한 이유]
- [반드시 Phase N-1 이후에 실행해야 하는 이유]
- [병렬 진행 시 충돌 가능성]

### 리스크
- [있으면 기술. 없으면 생략]
```

### 5. SPEC.md 기록

기존 Phase 번호 다음부터 이어서 추가.

### 6. 자동 이슈 생성

SPEC.md 기록 완료 후, 사용자에게 물어라:

```
분해된 Phase N개를 GitHub 이슈로 일괄 생성할까요? (y/n)
```

**승인 시**: 각 Phase에 대해 `/create-issue Phase N` 프로세스를 순차 실행해라.
- `/create-issue`가 중복 체크, 라벨 검증, 본문 작성, `--body-file` 방식 처리를 담당한다.
- `gh issue create`는 PreToolUse 훅으로 하드 차단되어 있다. Bash로 직접 호출 시도 금지.
- **경로 혼용 금지**: 한 세션에서 직접 호출과 `/create-issue`를 혼용하면 중복 생성 위험. `/create-issue` 경로만 사용해라.
- 동일 제목의 열린 이슈가 이미 있으면 `/create-issue`가 SKIP 처리한다 (멱등성 보장).
- 생성 후 SPEC.md에 이슈 번호 역기록 (`Phase N1 (#12)`)
- 모든 Phase 완료 후 아래 형식의 표를 보여줘라:

```
| 이슈 | Phase | 라벨 | 병렬 | 실행 커맨드 |
|------|-------|------|------|-----------|
| #12 | Phase N1: 제목 | feat | — | source scripts/create-worktree.sh feat 12 간략설명 |
| #13 | Phase N2: 제목 | fix | N1 후 | source scripts/create-worktree.sh fix 13 간략설명 |
| #14 | Phase N3: 제목 | feat | N2와 병렬 | source scripts/create-worktree.sh feat 14 간략설명 |
```

**거부 시**: 후속 안내만 표시.

### 7. 자동 커밋 & 푸시

모든 이슈 생성 + SPEC.md 역기록이 끝나면, SPEC.md 변경사항을 자동으로 커밋하고 푸시해라:

```bash
git add SPEC.md
git commit -m "docs: SPEC.md Phase N{시작}~N{끝} {기능 요약} 추가 (#{이슈1}, #{이슈2}, ...)"
git push
```

- 커밋 메시지에 Phase 범위와 이슈 번호를 포함할 것
- 푸시 실패 시 사용자에게 알려라 (권한/충돌 등)

### 8. 후속 안내

- `/create-issue`가 브랜치까지 자동 생성
- `git switch {브랜치명}` 또는 워크트리 (병렬 시): `source scripts/create-worktree.sh {타입} {이슈번호} {설명}`
- `/resolve-issue 이슈번호` 로 구현 + 커밋 + push
- IDE에서 확인/테스트 → 문제 발견 시: `/resolve-issue 이슈번호 문제 설명` (수정 모드)
- 만족하면: `/submit-pr 이슈번호` 로 PR 생성
- 병렬 가능 Phase는 동시 진행 가능
- **컨텍스트가 쌓였으면 `/compact` 권장**

---

## 원칙

1. **점진적 개선** — 각 Phase 후 프로젝트 정상 상태 유지 (불가 시 SPEC.md에 명시)
2. **과도한 추상화 금지** — 지금 필요한 만큼만
3. **파일 경로 필수** — 모든 변경 항목에 경로 포함
4. **검증 필수** — CLAUDE.md 빌드 커맨드 + 기능별 확인

## 출력

분해 결과를 요약 보고 → SPEC.md 업데이트 승인 → 기록.
