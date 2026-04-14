#!/bin/bash

#-----------------------------------------------------------------------
# merge 완료된 워크트리 일괄 정리
#
# 모든 워크트리를 조회하여, PR이 merged인 것만 자동 정리한다.
# 미merge 워크트리는 스킵하고 목록을 표시한다.
#
# 브랜치 네이밍: {타입}/{이슈번호}-{설명}
# 예: feat/12-add-schedule-repeat, fix/15-calendar-sync-error
#
# 사용법:
#   bash scripts/cleanup-worktrees.sh
#
# 메인 워크트리, 서브 워크트리 어디서든 실행 가능.
#-----------------------------------------------------------------------

# git 리포지토리 확인
if ! git rev-parse --git-dir &>/dev/null; then
    echo "❌ git 리포지토리가 아닙니다."
    exit 1
fi

# 메인 워크트리 경로 자동 감지 (어디서든 실행 가능)
MAIN_PATH=$(git worktree list --porcelain | head -1 | sed 's/^worktree //')

# 메인 워크트리로 이동 (서브 워크트리에서 실행 시 정리 후 getcwd 오류 방지)
cd "$MAIN_PATH" || { echo "❌ 메인 워크트리로 이동 실패: $MAIN_PATH"; exit 1; }

echo "🔍 워크트리 조회 중... (메인: $MAIN_PATH)"
echo ""

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

echo ""
echo "══════════════════════════════════"
echo "  정리 완료"
echo "══════════════════════════════════"
