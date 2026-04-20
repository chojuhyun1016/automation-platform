#!/bin/bash
# ============================================================
# Claude 워크플로우 템플릿 설치 스크립트
#
# 대상 프로젝트 루트에서 실행:
#   bash /path/to/templates/claude-workflow/install.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$(pwd)"

echo "Claude 워크플로우 템플릿 설치"
echo "대상: $TARGET_DIR"
echo ""

# Git 저장소 확인
if ! git rev-parse --git-dir &>/dev/null; then
    echo "ERROR: git 저장소가 아닙니다. 프로젝트 루트에서 실행하세요."
    exit 1
fi

# 백업 + 복사 함수
install_file() {
    local src="$1"
    local dest="$2"
    local dir
    dir=$(dirname "$dest")
    mkdir -p "$dir"

    if [ -f "$dest" ]; then
        echo "  [EXISTS] $dest → 백업 후 덮어쓰기"
        cp "$dest" "${dest}.bak"
    else
        echo "  [NEW]    $dest"
    fi
    cp "$src" "$dest"
}

# ─────────────────────────────────────────────────────────────
# commands
# ─────────────────────────────────────────────────────────────
echo ""
echo "commands 설치..."
for CMD in quick-fix resolve-conflict resolve-issue create-issue feature-breakdown submit-pr; do
    install_file "$SCRIPT_DIR/.claude/commands/${CMD}.md" "$TARGET_DIR/.claude/commands/${CMD}.md"
done

# ─────────────────────────────────────────────────────────────
# rules
# ─────────────────────────────────────────────────────────────
echo ""
echo "rules 설치..."
install_file "$SCRIPT_DIR/.claude/rules/agents.md" "$TARGET_DIR/.claude/rules/agents.md"

# ─────────────────────────────────────────────────────────────
# skills
# ─────────────────────────────────────────────────────────────
echo ""
echo "skills 설치..."
mkdir -p "$TARGET_DIR/.claude/skills/tdd-workflow"
install_file "$SCRIPT_DIR/.claude/skills/tdd-workflow/SKILL.md" "$TARGET_DIR/.claude/skills/tdd-workflow/SKILL.md"

# ─────────────────────────────────────────────────────────────
# scripts
# ─────────────────────────────────────────────────────────────
echo ""
echo "scripts 설치..."
for SCRIPT in create-worktree.sh cleanup-worktrees.sh check-docs-update.sh; do
    install_file "$SCRIPT_DIR/scripts/${SCRIPT}" "$TARGET_DIR/scripts/${SCRIPT}"
done
chmod +x "$TARGET_DIR/scripts/"*.sh

# ─────────────────────────────────────────────────────────────
# WORKFLOW.md
# ─────────────────────────────────────────────────────────────
echo ""
echo "WORKFLOW.md 설치..."
install_file "$SCRIPT_DIR/WORKFLOW.md" "$TARGET_DIR/WORKFLOW.md"

# ─────────────────────────────────────────────────────────────
# 완료
# ─────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "  설치 완료!"
echo "============================================"
echo ""
echo "설치된 파일:"
echo "  .claude/commands/  — 6개 커맨드"
echo "  .claude/rules/     — agents.md"
echo "  .claude/skills/    — tdd-workflow"
echo "  scripts/           — 3개 스크립트"
echo "  WORKFLOW.md        — 개발 워크플로우"
echo ""
echo "다음 단계:"
echo "  1. CLAUDE.md가 없으면 프로젝트에 맞게 작성"
echo "  2. SPEC.md가 없으면 빈 파일 생성"
echo "  3. PROJECT_SETUP_GUIDE.md 참고하여 커스터마이징"
echo "  4. Claude Code에서 /feature-breakdown 또는 /quick-fix 로 시작"
