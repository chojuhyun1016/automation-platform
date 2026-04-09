#!/bin/bash

#-----------------------------------------------------------------------
# git worktree 생성 + Claude Code 실행
#
# GitHub 이슈 번호를 받아 worktree를 생성하고 Claude를 실행한다.
# 반드시 source로 실행 (현재 셸 디렉토리 변경 필요)
#
# 사용법:
#   source scripts/create-worktree.sh <GitHub 이슈번호>
#
# 예시:
#   source scripts/create-worktree.sh 12    ← GitHub 이슈 #12 (Phase 번호 아님)
#   source scripts/create-worktree.sh 37    ← GitHub 이슈 #37
#
# 워크플로우 (번호 변환 과정):
#   /feature-breakdown              → SPEC.md에 Phase N1, N2 기록 (내부 계획 번호)
#   /create-issue Phase N1          → GitHub 이슈 #12 생성 + SPEC.md에 (#12) 역기록
#   source scripts/create-worktree.sh 12  ← GitHub 이슈 번호 사용 (Phase 번호 아님)
#   Claude에서: /resolve-issue 12   ← GitHub 이슈 번호로 조회 → SPEC.md Phase 매칭
#
# 병렬 실행:
#   터미널 1: source scripts/create-worktree.sh 12  → /resolve-issue 12
#   터미널 2: source scripts/create-worktree.sh 13  → /resolve-issue 13
#
# 정리 (PR merge 후):
#   cd <프로젝트 루트>
#   git worktree remove ../worktree/issue-12
#   git worktree prune
#-----------------------------------------------------------------------

# 인자 확인
if [ $# -eq 0 ]; then
    echo "❌ 이슈 번호를 입력하세요."
    echo "사용법: source scripts/create-worktree.sh <이슈번호>"
    echo "예시:   source scripts/create-worktree.sh 12"
    return 1
fi

# 프로젝트 루트 확인
if [ ! -f "CLAUDE.md" ]; then
    echo "❌ 프로젝트 루트에서 실행하세요."
    echo "   cd /Users/r00365/Work/workspace/automation-platform"
    return 1
fi

ISSUE_NUMBER=$1
BRANCH_NAME="issue-${ISSUE_NUMBER}"
WORKTREE_PATH="../worktree/${BRANCH_NAME}"

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
    echo ""
    echo "🚀 Claude 실행 중... /resolve-issue $ISSUE_NUMBER 로 시작하세요."
    echo ""
    claude --dangerously-skip-permissions
    return 0
fi

# 최신 main 기반으로 분기 (충돌 예방)
echo "📥 main 최신화 중..."
git fetch origin main --quiet 2>/dev/null

if git worktree add "$WORKTREE_PATH" -b "$BRANCH_NAME" origin/main 2>/dev/null || \
   git worktree add "$WORKTREE_PATH" "$BRANCH_NAME" 2>/dev/null; then
    echo "✅ 워크트리 생성: $WORKTREE_PATH (브랜치: $BRANCH_NAME, base: origin/main)"
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
