#!/bin/bash

#-----------------------------------------------------------------------
# merge 완료된 워크트리 일괄 정리
#
# 모든 issue-N 워크트리를 조회하여, PR이 merged인 것만 자동 정리한다.
# 미merge 워크트리는 스킵하고 목록을 표시한다.
#
# 사용법:
#   bash scripts/cleanup-worktrees.sh
#
# 프로젝트 루트에서 실행할 것.
#-----------------------------------------------------------------------

# 프로젝트 루트 확인
if [ ! -f "CLAUDE.md" ]; then
    echo "❌ 프로젝트 루트에서 실행하세요."
    echo "   cd /Users/r00365/Work/workspace/automation-platform"
    exit 1
fi

echo "🔍 워크트리 조회 중..."
echo ""

CLEANED=0
SKIPPED=0
SKIP_LIST=""

# 모든 워크트리 순회
git worktree list --porcelain | grep "^worktree " | while read -r LINE; do
    WT_PATH=$(echo "$LINE" | sed 's/^worktree //')

    # issue-N 패턴만 처리
    ISSUE_NUM=$(basename "$WT_PATH" | grep -oE '^issue-([0-9]+)$' | sed 's/issue-//')
    if [ -z "$ISSUE_NUM" ]; then
        continue
    fi

    # PR 상태 확인
    PR_STATE=$(gh pr list --head "issue-${ISSUE_NUM}" --state merged --json number -q '.[0].number' 2>/dev/null)

    if [ -n "$PR_STATE" ]; then
        echo "✅ issue-${ISSUE_NUM} — PR #${PR_STATE} merged → 정리"
        git worktree remove "$WT_PATH" --force 2>/dev/null
        git branch -d "issue-${ISSUE_NUM}" 2>/dev/null
        CLEANED=$((CLEANED + 1))
    else
        # merge 안 된 상태 확인
        PR_OPEN=$(gh pr list --head "issue-${ISSUE_NUM}" --state open --json number -q '.[0].number' 2>/dev/null)
        if [ -n "$PR_OPEN" ]; then
            echo "⏳ issue-${ISSUE_NUM} — PR #${PR_OPEN} 열림 (미merge) → 스킵"
        else
            echo "⚠️  issue-${ISSUE_NUM} — PR 없음 → 스킵"
        fi
        SKIPPED=$((SKIPPED + 1))
    fi
done

# git worktree prune
git worktree prune 2>/dev/null

echo ""
echo "══════════════════════════════════"
echo "  정리 완료"
echo "══════════════════════════════════"
