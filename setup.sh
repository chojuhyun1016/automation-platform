#!/bin/bash
# ============================================================
# automation-platform 프로젝트 구조 생성 스크립트
# Java 17 Gradle 멀티모듈 + Python 3.11 Docker
# ============================================================
set -euo pipefail

PROJECT_ROOT=$(pwd)
BASE_PKG="com/riman/automation"

echo "automation-platform 구조 생성 중..."

# ─────────────────────────────────────────────────────────────
# Gradle 공통
# ─────────────────────────────────────────────────────────────

mkdir -p gradle/wrapper

# ─────────────────────────────────────────────────────────────
# Java 모듈 — 모듈별 하위 패키지 구조
# ─────────────────────────────────────────────────────────────

# common: 공통 라이브러리 (예외, Enum, 유틸리티, SlackBlockBuilder)
for PKG in auth code exception model slack util; do
    mkdir -p "common/src/main/java/${BASE_PKG}/common/${PKG}"
done
mkdir -p common/src/main/resources
mkdir -p "common/src/test/java/${BASE_PKG}/common"
echo "   common/"

# clients: 외부 API 클라이언트 (Jira, Slack, Calendar, Confluence)
for PKG in anthropic calendar confluence http jira slack; do
    mkdir -p "clients/src/main/java/${BASE_PKG}/clients/${PKG}"
done
mkdir -p clients/src/main/resources
mkdir -p "clients/src/test/java/${BASE_PKG}/clients"
echo "   clients/"

# ingest: Lambda 진입점 (Slack 커맨드, Jira 웹훅 수신)
for PKG in dto facade handler payload security service util; do
    mkdir -p "ingest/src/main/java/${BASE_PKG}/ingest/${PKG}"
done
mkdir -p ingest/src/main/resources
mkdir -p "ingest/src/test/java/${BASE_PKG}/ingest"
echo "   ingest/"

# worker: SQS 소비자 (Jira-Calendar 동기화, 재택/부재/일정 처리)
for PKG in dto facade handler payload service; do
    mkdir -p "worker/src/main/java/${BASE_PKG}/worker/${PKG}"
done
mkdir -p worker/src/main/resources
mkdir -p "worker/src/test/java/${BASE_PKG}/worker"
echo "   worker/"

# scheduler: EventBridge 스케줄러 (일일/주간/월간 보고서)
for PKG in dto facade handler service tool; do
    mkdir -p "scheduler/src/main/java/${BASE_PKG}/scheduler/${PKG}"
done
mkdir -p scheduler/src/main/resources
mkdir -p "scheduler/src/test/java/${BASE_PKG}/scheduler"
echo "   scheduler/"

# groupware: Lambda 오케스트레이터 (그룹웨어 부재 신청)
for PKG in dto facade handler security service; do
    mkdir -p "groupware/src/main/java/${BASE_PKG}/groupware/${PKG}"
done
mkdir -p groupware/src/main/resources
mkdir -p "groupware/src/test/java/${BASE_PKG}/groupware"
echo "   groupware/"

# ─────────────────────────────────────────────────────────────
# groupware-bot: Python Docker (브라우저 자동화, Gradle 미포함)
# ─────────────────────────────────────────────────────────────

mkdir -p groupware-bot
touch groupware-bot/Dockerfile
touch groupware-bot/requirements.txt
touch groupware-bot/main.py
touch groupware-bot/groupware_client.py
touch groupware-bot/secrets_client.py
touch groupware-bot/slack_notifier.py
echo "   groupware-bot/ (Python Docker)"

# ─────────────────────────────────────────────────────────────
# config: S3 업로드용 런타임 설정 파일
# ─────────────────────────────────────────────────────────────

mkdir -p config/rules
touch config/config.json
touch config/scheduler-config.json
touch config/team-members.json
touch config/groupware-config.json
touch config/announcements.json
echo "   config/"

# ─────────────────────────────────────────────────────────────
# scripts: 개발/운영 스크립트
# ─────────────────────────────────────────────────────────────

mkdir -p scripts
touch scripts/create-worktree.sh
touch scripts/cleanup-worktrees.sh
touch scripts/check-docs-update.sh
echo "   scripts/"

# ─────────────────────────────────────────────────────────────
# Gradle Wrapper 생성
# ─────────────────────────────────────────────────────────────

if command -v gradle &>/dev/null; then
    echo ""
    echo "Gradle Wrapper 생성 중 (gradle 8.10)..."
    gradle wrapper --gradle-version 8.10
    echo "   Gradle Wrapper"
else
    echo ""
    echo "gradle 미설치 — Wrapper 생성 건너뜀"
    echo "   수동 실행: gradle wrapper --gradle-version 8.10"
fi

# ─────────────────────────────────────────────────────────────
# .gitignore 생성 (없을 때만)
# ─────────────────────────────────────────────────────────────

if [ ! -f ".gitignore" ]; then
cat > .gitignore << 'EOF'
# Gradle
.gradle/
build/
*/build/

# IntelliJ
.idea/
*.iml
out/

# Python
groupware-bot/__pycache__/
groupware-bot/*.pyc
groupware-bot/.pytest_cache/

# OS
.DS_Store
Thumbs.db

# Secrets
config/google-credentials.json
EOF
    echo "   .gitignore"
fi

# ─────────────────────────────────────────────────────────────
# 완료
# ─────────────────────────────────────────────────────────────

echo ""
echo "구조 생성 완료!"
echo ""
echo "프로젝트 루트: $PROJECT_ROOT"
echo ""
echo "다음 단계:"
echo "  1. make help          — 빌드/배포 명령 확인"
echo "  2. make build         — 전체 빌드"
echo "  3. make deploy-config — config/ S3 업로드"
echo ""
echo "개발 워크플로우:"
echo "  /feature-breakdown → /create-issue → /resolve-issue → /submit-pr"
echo "  /quick-fix         — 이슈 없이 즉석 수정"
