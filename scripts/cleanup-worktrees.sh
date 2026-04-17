#!/bin/bash

#-----------------------------------------------------------------------
# merge 완료된 워크트리 일괄 정리 + SPEC.md Phase 체크박스 자동 갱신
#
# 모든 워크트리를 조회하여, PR이 merged인 것만 자동 정리한다.
# 정리된 Phase의 SPEC.md 체크박스를 [x]로 업데이트하고 자동 커밋한다.
# 미merge 워크트리는 스킵하고 목록을 표시한다.
#
# 브랜치 네이밍: {타입}/{이슈번호}-{설명}
# 예: feat/12-add-schedule-repeat, fix/15-calendar-sync-error
#
# 사용법:
#   bash scripts/cleanup-worktrees.sh
#
# 메인 워크트리에서 실행해야 한다 (서브 워크트리 내부 실행 감지 시 안내).
#-----------------------------------------------------------------------

set -euo pipefail

# git 리포지토리 확인
if ! git rev-parse --git-dir &>/dev/null; then
    echo "❌ git 리포지토리가 아닙니다."
    exit 1
fi

# 메인 워크트리 경로 자동 감지 (어디서든 실행 가능)
MAIN_PATH=$(git worktree list --porcelain | head -1 | sed 's/^worktree //')

# 워크트리 내부 실행 감지 → 부모 셸 CWD 무효화 방지
CALLER_CWD=$(pwd -P 2>/dev/null || echo "")
if [ "$CALLER_CWD" != "$MAIN_PATH" ]; then
    if git worktree list --porcelain | grep -q "^worktree $CALLER_CWD$"; then
        echo "🚫 워크트리 내부에서 실행 감지: $CALLER_CWD"
        echo "   부모 셸 CWD가 무효화될 수 있습니다."
        echo "   다음 명령으로 실행하세요:"
        echo "   cd $MAIN_PATH && bash scripts/cleanup-worktrees.sh"
        exit 1
    fi
fi

# 메인 워크트리로 이동 (서브 워크트리에서 실행 시 정리 후 getcwd 오류 방지)
cd "$MAIN_PATH" || { echo "❌ 메인 워크트리로 이동 실패: $MAIN_PATH"; exit 1; }

SPEC_FILE="$MAIN_PATH/SPEC.md"

#-----------------------------------------------------------------------
# SPEC.md Phase 체크박스 갱신 함수
#
# SPEC.md에서 해당 이슈 번호의 Phase 섹션을 찾아:
#   1. 최상위 체크박스: - [x] Phase N 완료 (PR #N)
#   2. 수정/개선 + 검증 내 모든 [ ] → [x]
#   3. 다음 Phase 전제조건의 해당 Phase 참조도 [x] 처리
#-----------------------------------------------------------------------
update_spec_phase() {
    local issue_num="$1"
    local pr_num="$2"

    if [ ! -f "$SPEC_FILE" ]; then
        return
    fi

    # Phase 헤더 라인 찾기: ## Phase N...: ... (#이슈번호)
    local header_line
    header_line=$(grep -n "^## Phase N[0-9]*:.*#${issue_num})" "$SPEC_FILE" | head -1 | cut -d: -f1)
    if [ -z "$header_line" ]; then
        return
    fi

    local total_lines
    total_lines=$(wc -l < "$SPEC_FILE")

    # 다음 Phase 헤더 라인 찾기 (섹션 경계)
    local next_header_line
    next_header_line=$(awk "NR > $header_line && /^## Phase N[0-9]/ { print NR; exit }" "$SPEC_FILE")
    if [ -z "$next_header_line" ]; then
        next_header_line=$((total_lines + 1))
    fi

    # 이미 완료 상태인지 확인
    local top_checkbox
    top_checkbox=$(sed -n "$((header_line + 1)),$((header_line + 3))p" "$SPEC_FILE" | grep -c '\- \[x\] Phase N[0-9]* 완료' || true)
    if [ "$top_checkbox" -gt 0 ]; then
        return
    fi

    # Phase 섹션 내 모든 [ ] → [x] 변환
    sed -i '' "${header_line},${next_header_line}{
        s/- \[ \] Phase N\([0-9]*\) 완료$/- [x] Phase N\1 완료 (PR #${pr_num})/
        s/- \[ \]/- [x]/g
    }" "$SPEC_FILE"

    # Phase 번호 추출 (N14, N15 등)
    local phase_id
    phase_id=$(sed -n "${header_line}p" "$SPEC_FILE" | grep -oE 'Phase N[0-9.]+' | head -1)

    # 다음 Phase의 전제조건에서 이 Phase 참조 업데이트
    if [ -n "$phase_id" ] && [ "$next_header_line" -le "$total_lines" ]; then
        # 다음 Phase 섹션 범위 내에서 전제조건 체크박스 업데이트
        local next_next_header
        next_next_header=$(awk "NR > $next_header_line && /^## Phase N[0-9]/ { print NR; exit }" "$SPEC_FILE")
        if [ -z "$next_next_header" ]; then
            next_next_header=$((total_lines + 1))
        fi
        sed -i '' "${next_header_line},${next_next_header}{
            s/- \[ \] ${phase_id} 완료$/- [x] ${phase_id} 완료 (PR #${pr_num})/
            s/- \[ \] ${phase_id} 완료 */- [x] ${phase_id} 완료 (PR #${pr_num})/
        }" "$SPEC_FILE"
    fi

    echo "   📝 SPEC.md ${phase_id} (#${issue_num}) → PR #${pr_num} 완료 처리"
}

echo "🔍 워크트리 조회 중... (메인: $MAIN_PATH)"
echo ""

# 머지된 PR 정보 수집 (파이프 서브셸 문제 방지를 위해 임시 파일 사용)
MERGED_LIST=$(mktemp)
trap 'rm -f "$MERGED_LIST"' EXIT

# 모든 워크트리 순회
git worktree list --porcelain | grep "^worktree " | while read -r LINE; do
    WT_PATH=$(echo "$LINE" | sed 's/^worktree //')

    # 메인 워크트리는 스킵
    if [ "$WT_PATH" = "$MAIN_PATH" ]; then
        continue
    fi

    # 브랜치명 추출
    BRANCH=$(git -C "$WT_PATH" branch --show-current 2>/dev/null)
    if [ -z "$BRANCH" ]; then
        continue
    fi

    # 이슈 번호 추출 ({타입}/{번호}-{설명} 또는 issue-{번호})
    ISSUE_NUM=$(echo "$BRANCH" | grep -oE '/([0-9]+)-' | grep -oE '[0-9]+' | head -1)
    if [ -z "$ISSUE_NUM" ]; then
        # 레거시 issue-N 패턴 지원
        ISSUE_NUM=$(echo "$BRANCH" | grep -oE '^issue-([0-9]+)$' | sed 's/issue-//')
    fi
    if [ -z "$ISSUE_NUM" ]; then
        continue
    fi

    # PR 상태 확인
    PR_STATE=$(gh pr list --head "$BRANCH" --state merged --json number -q '.[0].number' 2>/dev/null)

    if [ -n "$PR_STATE" ]; then
        echo "✅ $BRANCH — PR #${PR_STATE} merged → 정리"
        echo "${ISSUE_NUM}:${PR_STATE}" >> "$MERGED_LIST"
        git worktree remove "$WT_PATH" --force 2>/dev/null
        git branch -d "$BRANCH" 2>/dev/null || git branch -D "$BRANCH" 2>/dev/null
    else
        PR_OPEN=$(gh pr list --head "$BRANCH" --state open --json number -q '.[0].number' 2>/dev/null)
        if [ -n "$PR_OPEN" ]; then
            echo "⏳ $BRANCH — PR #${PR_OPEN} 열림 (미merge) → 스킵"
        else
            echo "⚠️  $BRANCH — PR 없음 → 스킵"
        fi
    fi
done

git worktree prune 2>/dev/null

#-----------------------------------------------------------------------
# SPEC.md 자동 갱신
#-----------------------------------------------------------------------
SPEC_UPDATED=0
if [ -f "$SPEC_FILE" ] && [ -s "$MERGED_LIST" ]; then
    echo ""
    echo "📄 SPEC.md Phase 체크박스 동기화..."
    while IFS=: read -r ISSUE_NUM PR_NUM; do
        update_spec_phase "$ISSUE_NUM" "$PR_NUM"
        SPEC_UPDATED=1
    done < "$MERGED_LIST"
fi

# SPEC.md 변경 시 자동 커밋
if [ "$SPEC_UPDATED" -eq 1 ] && git diff --quiet "$SPEC_FILE" 2>/dev/null; then
    SPEC_UPDATED=0
fi
if [ "$SPEC_UPDATED" -eq 1 ]; then
    echo ""
    echo "💾 SPEC.md 변경사항 커밋 중..."
    git add "$SPEC_FILE"
    git commit -m "docs: SPEC.md Phase 완료 체크박스 자동 동기화"
    echo "   ✅ 커밋 완료 (push는 수동으로 실행하세요)"
fi

echo ""
echo "══════════════════════════════════"
echo "  정리 완료"
echo "══════════════════════════════════"
