
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

완료 후 → `source scripts/create-worktree.sh {타입} {이슈번호} {설명}` → `/resolve-issue {이슈번호}`

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

**라벨**: `gh label list`로 확인 후 매핑. 필요한 라벨이 없으면 생성해라.

| 유형 | GitHub 라벨 | 색상 |
|------|-----------|------|
| feature / enhancement | `enhancement` | `a2eeef` |
| bug | `bug` | `d73a4a` |
| refactor | `refactor` | `fbca04` |
| chore | `chore` | `ededed` |

라벨이 리포에 없으면 생성 후 사용:
```bash
gh label create "라벨명" --description "설명" --color "색상코드" 2>/dev/null || true
```

### 4. 중복 체크 (필수)

이슈 생성 직전에 **동일 제목의 열린 이슈가 이미 있는지** 반드시 확인해라:
```bash
TITLE="제목 전체 문자열"
EXISTING=$(gh issue list --state open --search "\"$TITLE\" in:title" --json number,title --jq '.[0].number')
if [ -n "$EXISTING" ]; then
  echo "이미 존재: #$EXISTING - 중복 생성 SKIP"
  # SPEC.md 역기록(5단계)에 기존 번호 사용. 6단계 워크트리 제안도 기존 번호로 진행.
  # 이후 로직을 EXISTING 값으로 이어가라.
else
  # 5번 생성 단계로 진행
  :
fi
```

- 중복 발견 시: 기존 이슈 번호를 그대로 사용하여 SPEC.md 역기록/워크트리 제안 진행
- 중복 아님: 다음 5번 단계로 이동

### 5. 사용자 확인 후 생성

내용을 보여주고 확인 후 생성. `--body` 인라인 heredoc은 사용 금지 (Claude Code 보안 체크에서 마크다운 `##`/`###`를 차단함).
반드시 `--body-file` 방식을 사용해라:
```bash
cat > /tmp/issue-body.md <<'EOF'
이슈 본문 (마크다운)
EOF
gh issue create --title "제목" --label "라벨" --body-file /tmp/issue-body.md
```
수정 요청 시 반영 후 재확인.

### 6. SPEC.md 역기록 (Phase 기반)

이슈 생성 후 **2곳**을 업데이트해라:

**A. Phase 제목에 이슈 번호 추가:**
  `## Phase N1: 유닛 테스트` → `## Phase N1: 유닛 테스트 (#12)`

**B. 실행 가이드 표에 실행 커맨드 기록:**
  `| N1 | 미생성 | feat | ... |` → `| N1 | #12 | feat | source scripts/create-worktree.sh feat 12 unit-test-setup |`

이 매핑이 있어야 `/resolve-issue 12` 실행 시 SPEC.md에서 해당 Phase를 찾을 수 있고,
사용자가 SPEC.md만 열어도 실행 커맨드를 바로 복사할 수 있다.

### 7. 워크트리 생성 제안

이슈 생성 직후, 사용자에게 **워크트리 생성 여부를 질문**해라:

유형에서 브랜치 타입을 매핑:

| 유형 | 브랜치 타입 |
|------|-----------|
| feature / enhancement | `feat` |
| bug | `fix` |
| refactor | `refactor` |
| chore | `chore` |

**질문 형식**:
```
워크트리를 생성하시겠습니까? 아래 커맨드를 터미널에서 실행하세요:

  source scripts/create-worktree.sh {타입} {이슈번호} {설명}

워크트리에서 Claude가 자동 실행되며, /resolve-issue {이슈번호} 로 작업을 시작할 수 있습니다.
```

> `source`는 현재 셸에서 실행해야 하므로 Claude Bash 도구로 실행 불가.
> 사용자에게 커맨드를 제시하고 직접 실행하도록 안내해라.

사용자가 "다른 Phase"를 원하면 `/create-issue Phase N+1`을 안내.

### 8. 자동 커밋 & 푸시

SPEC.md 역기록이 끝나면, 변경사항을 자동으로 커밋하고 푸시해라:

```bash
git add SPEC.md
git commit -m "docs: SPEC.md Phase N{번호} 이슈 #{번호} 역기록"
git push
```

- `/feature-breakdown`에서 일괄 호출된 경우: 마지막 Phase 완료 후 `feature-breakdown`이 한 번에 커밋하므로 여기서는 **커밋하지 마라**
- 단독 `/create-issue` 호출인 경우에만 커밋/푸시 실행
