
# ═══════════════════════════════════════════════════════════
# 파일 2: .claude/commands/create-issue.md
# ═══════════════════════════════════════════════════════════


# Create Issue

입력 내용을 기반으로 GitHub 이슈를 생성한다.

**이슈 내용**: $ARGUMENTS

---

## 사용 방법

```
# Phase를 이슈로 변환:
/create-issue Phase N1

# 자유 형식:
/create-issue 로그인 시 세션 만료 처리 버그
/create-issue 월간 보고서 프로젝트별 분리 기능
```

완료 후 → `source scripts/create-worktree.sh 이슈번호` → `/resolve-issue 이슈번호`

> **번호 변환**: `/create-issue Phase N1` 실행 → GitHub이 이슈 번호(#12)를 자동 부여.
> 이후 워크트리/resolve-issue에서는 Phase 번호가 아닌 **GitHub 이슈 번호**를 사용한다.

---

## 프로세스

### 0. SPEC.md 확인 (Phase 기반)

$ARGUMENTS가 "Phase N" 형식이면:
- SPEC.md에서 해당 Phase를 읽어라
- Phase 내용을 이슈 본문에 반영, 메타에서 라벨/우선순위 가져와라
- 2단계(코드 탐색)는 건너뛰어라

일반 텍스트이면 1단계부터 진행.

### 1. 이슈 분석

- **유형**: feature / bug / refactor / enhancement / chore
- **영향 범위**: 어떤 파일/모듈/기능에 관련되는가
- **우선순위**: high / medium / low

정보 부족 시 질문해라.

### 2. 코드 탐색

**Explore 서브에이전트를 context fork로 실행.**

- 관련 파일 경로
- 현재 동작 vs 기대 동작 (bug) / 현재 상태 (feature)

### 3. 이슈 작성

**제목**: `[유형] 간결한 제목` (50자 이내)

**본문**:

```markdown
## 설명
[핵심 내용 1-2문장]

## 현재 동작
[bug: 현재 동작 / feature: 현재 상태]

## 기대 동작
[bug: 올바른 동작 / feature: 구현 후 결과]

## 관련 파일
- `파일 경로` — 역할

## 작업 항목
- [ ] 작업 1
- [ ] 작업 2

## 검증 기준
- [ ] 빌드 성공
- [ ] [기능별 검증]

## 참고
[Phase 기반이면: `SPEC.md Phase N 참조`]
```

**라벨**: CLAUDE.md 라벨 체계가 있으면 따르고, 없으면 유형 + 우선순위.

### 4. 사용자 확인 후 생성

내용을 보여주고 확인 후 `gh issue create` 실행.
수정 요청 시 반영 후 재확인.

### 5. SPEC.md 역기록 (Phase 기반)

이슈 생성 후 SPEC.md Phase 제목에 **GitHub 이슈 번호**를 추가:
  예: `## Phase N1: 유닛 테스트` → `## Phase N1: 유닛 테스트 (#12)`

이 매핑이 있어야 `/resolve-issue 12` 실행 시 SPEC.md에서 해당 Phase를 찾을 수 있다.

### 6. 후속 안내

```
다음 단계:
  source scripts/create-worktree.sh 이슈번호
  → Claude에서: /resolve-issue 이슈번호

다른 Phase: /create-issue Phase N+1
```
