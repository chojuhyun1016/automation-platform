#!/bin/bash

#-----------------------------------------------------------------------
# git worktree 생성 + Claude Code 실행
#
# GitHub 이슈 번호와 타입/설명을 받아 worktree를 생성하고 Claude를 실행한다.
# 반드시 source로 실행 (현재 셸 디렉토리 변경 필요)
#
# 사용법:
#   source scripts/create-worktree.sh <타입> <이슈번호> <설명>
#
# 브랜치 네이밍: {타입}/{이슈번호}-{설명}
#
# 타입:
#   feat      새 기능
#   fix       버그 수정
#   refactor  리팩터링
#   chore     설정/문서/빌드
#   hotfix    긴급 수정
#
# 예시:
#   source scripts/create-worktree.sh feat 12 add-schedule-repeat
#   source scripts/create-worktree.sh fix 15 calendar-sync-error
#   source scripts/create-worktree.sh refactor 18 extract-config-service
#   source scripts/create-worktree.sh hotfix 22 slack-timeout
#
# 워크플로우 (번호 변환 과정):
#   /feature-breakdown              → SPEC.md에 Phase N1, N2 기록
#   /create-issue Phase N1          → GitHub 이슈 #12 생성 + SPEC.md 역기록
#   source scripts/create-worktree.sh feat 12 add-schedule-repeat
#   Claude에서: /resolve-issue 12
#
# 병렬 실행:
#   터미널 1: source scripts/create-worktree.sh feat 12 add-schedule-repeat
#   터미널 2: source scripts/create-worktree.sh feat 13 add-absence-type
#
# 정리 (PR merge 후):
#   cd <프로젝트 루트>
#   bash scripts/cleanup-worktrees.sh
#-----------------------------------------------------------------------

# 인자 확인
if [ $# -lt 3 ]; then
    echo "❌ 사용법: source scripts/create-worktree.sh <타입> <이슈번호> <설명>"
    echo ""
    echo "타입: feat | fix | refactor | chore | hotfix"
    echo ""
    echo "예시:"
    echo "  source scripts/create-worktree.sh feat 12 add-schedule-repeat"
    echo "  source scripts/create-worktree.sh fix 15 calendar-sync-error"
    return 1
fi

# 프로젝트 루트 확인
if [ ! -f "CLAUDE.md" ]; then
    echo "❌ 프로젝트 루트에서 실행하세요."
    echo "   cd /Users/r00365/Work/workspace/automation-platform"
    return 1
fi

TYPE=$1
ISSUE_NUMBER=$2
DESCRIPTION=$3
BRANCH_NAME="${TYPE}/${ISSUE_NUMBER}-${DESCRIPTION}"
WORKTREE_DIR="${TYPE}-${ISSUE_NUMBER}-${DESCRIPTION}"
WORKTREE_PATH="../worktree/${WORKTREE_DIR}"

# 타입 유효성 확인
case "$TYPE" in
    feat|fix|refactor|chore|hotfix) ;;
    *)
        echo "❌ 유효하지 않은 타입: $TYPE"
        echo "허용: feat | fix | refactor | chore | hotfix"
        return 1
        ;;
esac

# 이슈 존재 여부 확인
if ! gh issue view "$ISSUE_NUMBER" &>/dev/null; then
    echo "⚠️  GitHub 이슈 #$ISSUE_NUMBER 가 존재하지 않습니다."
    echo -n "계속하시겠습니까? (y/n): "
    read -r CONFIRM
    if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
        echo "취소됨."
        return 1
    fi
fi

# 이미 존재하는 워크트리 처리
if [ -d "$WORKTREE_PATH" ]; then
    echo "ℹ️  워크트리 이미 존재: $WORKTREE_PATH — 이동합니다."
    cd "$WORKTREE_PATH" || return 1
    echo "➡️  $(pwd)"
    echo "🔀 브랜치: $BRANCH_NAME"
    echo ""
    echo "🚀 Claude 실행 중... /resolve-issue $ISSUE_NUMBER 로 시작하세요."
    echo ""
    claude --dangerously-skip-permissions -c
    return 0
fi

# 최신 main 기반으로 분기 (충돌 예방)
echo "📥 main 최신화 중..."
git fetch origin main --quiet 2>/dev/null

if git worktree add "$WORKTREE_PATH" -b "$BRANCH_NAME" origin/main 2>/dev/null || \
   git worktree add "$WORKTREE_PATH" "$BRANCH_NAME" 2>/dev/null; then
    echo "✅ 워크트리 생성: $WORKTREE_PATH"
    echo "🔀 브랜치: $BRANCH_NAME (base: origin/main)"
    cd "$WORKTREE_PATH" || return 1
    echo "➡️  $(pwd)"
    echo ""
    echo "🚀 Claude 실행 중... /resolve-issue $ISSUE_NUMBER 로 시작하세요."
    echo ""
    claude --dangerously-skip-permissions
else
    echo "❌ 워크트리 생성 실패. 확인: git worktree list"
    return 1
fi
