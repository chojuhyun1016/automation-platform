#!/bin/bash
# ============================================================
# Clean Build
# 빌드 대상: ingest / worker / scheduler / groupware
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACCOUNT_ID="747461205838"
REGION="ap-northeast-2"
PROFILE="personal"
S3_BUCKET="automation-platform-${ACCOUNT_ID}"

echo "▶ [1/3] Stopping Gradle daemons..."
./gradlew --stop 2>/dev/null || true

echo "▶ [2/3] Cleaning build artifacts..."
./gradlew clean --no-daemon
rm -rf .gradle

echo "▶ [3/3] Building all modules..."
./gradlew shadowJar --no-daemon

echo ""
echo "✅ 빌드 완료:"
find . -name "*.jar" -path "*/build/libs/*" | while read JAR; do
    echo "   $JAR ($(ls -lh "$JAR" | awk '{print $5}'))"
done

# ─────────────────────────────────────────────────────────────
# 배포 안내 (직접 배포 / S3 경유 배포)
# ─────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════════════════════════"
echo "  직접 배포 (jar 10MB 이하일 때)"
echo "══════════════════════════════════════════════════════════"
echo ""
echo "  # ingest"
echo "  aws lambda update-function-code \\"
echo "    --function-name automation-ingest \\"
echo "    --zip-file fileb://ingest/build/libs/automation-ingest.jar \\"
echo "    --region ${REGION} --profile ${PROFILE}"
echo ""
echo "  # worker"
echo "  aws lambda update-function-code \\"
echo "    --function-name automation-worker \\"
echo "    --zip-file fileb://worker/build/libs/automation-worker.jar \\"
echo "    --region ${REGION} --profile ${PROFILE}"
echo ""
echo "  # scheduler"
echo "  aws lambda update-function-code \\"
echo "    --function-name AutomationScheduler \\"
echo "    --zip-file fileb://scheduler/build/libs/automation-scheduler.jar \\"
echo "    --region ${REGION} --profile ${PROFILE}"
echo ""
echo "  # groupware (신규)"
echo "  aws lambda update-function-code \\"
echo "    --function-name automation-groupware \\"
echo "    --zip-file fileb://groupware/build/libs/automation-groupware.jar \\"
echo "    --region ${REGION} --profile ${PROFILE}"

echo ""
echo "══════════════════════════════════════════════════════════"
echo "  S3 경유 배포 (jar 10MB 초과 시 — 현재 권장)"
echo "══════════════════════════════════════════════════════════"

for MODULE in ingest worker scheduler groupware; do
    # groupware → riman-automation-groupware, 나머지는 automation-{module}
    if [ "$MODULE" = "ingest" ]; then
        FUNCTION_NAME="AutomationWebhookIngest"
    elif [ "$MODULE" = "groupware" ]; then
        FUNCTION_NAME="automation-groupware"
    elif [ "$MODULE" = "worker" ]; then
        FUNCTION_NAME="AutomationWebhookWorker"
    elif [ "$MODULE" = "scheduler" ]; then
        FUNCTION_NAME="AutomationScheduler"
    else
        FUNCTION_NAME="automation-${MODULE}"
    fi

    JAR_FILE="${MODULE}/build/libs/automation-${MODULE}.jar"
    S3_KEY="jar/automation-${MODULE}.jar"

    echo ""
    echo "  # [${MODULE}] S3 업로드"
    echo "  aws s3 cp ${JAR_FILE} \\"
    echo "    s3://${S3_BUCKET}/${S3_KEY} \\"
    echo "    --profile ${PROFILE}"
    echo ""
    echo "  # [${MODULE}] Lambda 업데이트"
    echo "  aws lambda update-function-code \\"
    echo "    --function-name ${FUNCTION_NAME} \\"
    echo "    --s3-bucket ${S3_BUCKET} \\"
    echo "    --s3-key ${S3_KEY} \\"
    echo "    --region ${REGION} \\"
    echo "    --profile ${PROFILE}"
done

echo ""
echo "══════════════════════════════════════════════════════════"
echo "  groupware-bot Docker 배포 (ECR)"
echo "══════════════════════════════════════════════════════════"
echo ""
echo "  make push-bot"
echo "  또는:"
echo "  aws ecr get-login-password --region ${REGION} --profile ${PROFILE} | \\"
echo "    docker login --username AWS --password-stdin \\"
echo "    ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
echo "  docker build --platform linux/amd64 -t groupware-bot:latest groupware-bot/"
echo "  docker tag groupware-bot:latest \\"
echo "    ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/automation/groupware-bot:latest"
echo "  docker push \\"
echo "    ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/automation/groupware-bot:latest"
