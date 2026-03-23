#!/bin/bash
# ============================================================
# automation-platform 초기 프로젝트 구조 생성 스크립트
# 기존 모듈: ingest, worker, scheduler
# 신규 모듈: groupware (Java Lambda), groupware-bot (Python Docker)
# ============================================================
set -euo pipefail

PROJECT_ROOT=$(pwd)

echo "🚀 automation-platform 구조 생성 중..."

# ─────────────────────────────────────────────────────────────
# Gradle 공통
# ─────────────────────────────────────────────────────────────

mkdir -p gradle/wrapper

# ─────────────────────────────────────────────────────────────
# 기존 Java 모듈
# ─────────────────────────────────────────────────────────────

for MODULE in common clients ingest worker scheduler; do
    mkdir -p "${MODULE}/src/main/java/com/riman/automation/${MODULE}"
    mkdir -p "${MODULE}/src/main/resources"
    mkdir -p "${MODULE}/src/test/java/com/riman/automation/${MODULE}"
    echo "   ✔ ${MODULE}/"
done

# ─────────────────────────────────────────────────────────────
# groupware Java Lambda 모듈 (신규)
# ─────────────────────────────────────────────────────────────

mkdir -p groupware/src/main/java/com/riman/automation/groupware/handler
mkdir -p groupware/src/main/java/com/riman/automation/groupware/facade
mkdir -p groupware/src/main/java/com/riman/automation/groupware/service
mkdir -p groupware/src/main/java/com/riman/automation/groupware/dto
mkdir -p groupware/src/main/resources
mkdir -p groupware/src/test/java/com/riman/automation/groupware
echo "   ✔ groupware/ (Java Lambda)"

# ─────────────────────────────────────────────────────────────
# groupware-bot Python Docker (신규)
# ─────────────────────────────────────────────────────────────

mkdir -p groupware-bot
touch groupware-bot/Dockerfile
touch groupware-bot/requirements.txt
touch groupware-bot/main.py
touch groupware-bot/groupware_client.py
touch groupware-bot/secrets_client.py
touch groupware-bot/slack_notifier.py
echo "   ✔ groupware-bot/ (Python Docker)"

# ─────────────────────────────────────────────────────────────
# Gradle Wrapper 생성
# ─────────────────────────────────────────────────────────────

if command -v gradle &>/dev/null; then
    echo ""
    echo "▶ Gradle Wrapper 생성 중 (gradle 8.5)..."
    gradle wrapper --gradle-version 8.5
    echo "   ✔ Gradle Wrapper"
else
    echo ""
    echo "⚠️  gradle 미설치 — Wrapper 생성 건너뜀"
    echo "   수동 실행: gradle wrapper --gradle-version 8.5"
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
EOF
    echo "   ✔ .gitignore"
fi

# ─────────────────────────────────────────────────────────────
# 완료
# ─────────────────────────────────────────────────────────────

echo ""
echo "✅ 구조 생성 완료!"
echo ""
echo "📂 프로젝트 루트: $PROJECT_ROOT"
echo ""
echo "다음 단계:"
echo "  1. settings.gradle 에 'groupware' 추가"
echo "  2. groupware/build.gradle 작성"
echo "  3. groupware-bot/ Python 파일 작성"
echo "  4. make help  — 빌드/배포 명령 확인"
