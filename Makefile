# ============================================================
# automation-platform Makefile
#
# ACCOUNT_ID: AWS 콘솔 우측 상단 계정 메뉴에서 확인
#   또는: aws sts get-caller-identity --query Account --output text
# ============================================================

ACCOUNT_ID    := 747461205838
REGION        := ap-northeast-2
PROFILE       := personal
S3_BUCKET     := automation-platform-$(ACCOUNT_ID)
ECR_REPO      := $(ACCOUNT_ID).dkr.ecr.$(REGION).amazonaws.com/automation/groupware-bot

# M1/M2/M3 Mac → linux/amd64 필수 (Fargate는 x86_64)
PLATFORM      := linux/amd64

# ─────────────────────────────────────────────────────────────
# Java Lambda 빌드 (shadowJar)
# ─────────────────────────────────────────────────────────────

.PHONY: build
build:
	./gradlew shadowJar --no-daemon
	@echo ""
	@echo "✅ 빌드 완료:"
	@find . -name "*.jar" -path "*/build/libs/*" | while read JAR; do \
		echo "   $$JAR ($$(ls -lh $$JAR | awk '{print $$5}'))"; \
	done

.PHONY: build-ingest
build-ingest:
	./gradlew :ingest:shadowJar --no-daemon

.PHONY: build-worker
build-worker:
	./gradlew :worker:shadowJar --no-daemon

.PHONY: build-scheduler
build-scheduler:
	./gradlew :scheduler:shadowJar --no-daemon

.PHONY: build-groupware
build-groupware:
	./gradlew :groupware:shadowJar --no-daemon

.PHONY: clean
clean:
	./gradlew --stop 2>/dev/null || true
	./gradlew clean --no-daemon
	rm -rf .gradle

# ─────────────────────────────────────────────────────────────
# Python Docker (groupware-bot)
# ─────────────────────────────────────────────────────────────

.PHONY: build-bot
build-bot:
	docker build --platform $(PLATFORM) -t groupware-bot:latest groupware-bot/

.PHONY: ecr-login
ecr-login:
	aws ecr get-login-password --region $(REGION) --profile $(PROFILE) | \
	docker login --username AWS --password-stdin \
	$(ACCOUNT_ID).dkr.ecr.$(REGION).amazonaws.com

.PHONY: push-bot
push-bot: ecr-login build-bot
	docker tag groupware-bot:latest $(ECR_REPO):latest
	docker push $(ECR_REPO):latest
	@echo "✅ ECR 푸시 완료: $(ECR_REPO):latest"

# ─────────────────────────────────────────────────────────────
# Lambda 배포 (S3 경유 — jar 10MB 초과 시 필수)
# ─────────────────────────────────────────────────────────────

.PHONY: deploy-ingest
deploy-ingest: build-ingest
	aws s3 cp ingest/build/libs/automation-ingest.jar \
	  s3://$(S3_BUCKET)/jar/automation-ingest.jar \
	  --profile $(PROFILE)
	aws lambda update-function-code \
	  --function-name AutomationWebhookIngest \
	  --s3-bucket $(S3_BUCKET) \
	  --s3-key jar/automation-ingest.jar \
	  --region $(REGION) \
	  --profile $(PROFILE)
	@echo "✅ ingest Lambda 배포 완료 (AutomationWebhookIngest)"

.PHONY: deploy-worker
deploy-worker: build-worker
	aws s3 cp worker/build/libs/automation-worker.jar \
	  s3://$(S3_BUCKET)/jar/automation-worker.jar \
	  --profile $(PROFILE)
	aws lambda update-function-code \
	  --function-name automation-worker \
	  --s3-bucket $(S3_BUCKET) \
	  --s3-key jar/automation-worker.jar \
	  --region $(REGION) \
	  --profile $(PROFILE)
	@echo "✅ worker Lambda 배포 완료"

.PHONY: deploy-scheduler
deploy-scheduler: build-scheduler
	aws s3 cp scheduler/build/libs/automation-scheduler.jar \
	  s3://$(S3_BUCKET)/jar/automation-scheduler.jar \
	  --profile $(PROFILE)
	aws lambda update-function-code \
	  --function-name AutomationScheduler \
	  --s3-bucket $(S3_BUCKET) \
	  --s3-key jar/automation-scheduler.jar \
	  --region $(REGION) \
	  --profile $(PROFILE)
	@echo "✅ scheduler Lambda 배포 완료"

.PHONY: deploy-groupware
deploy-groupware: build-groupware
	aws s3 cp groupware/build/libs/automation-groupware.jar \
	  s3://$(S3_BUCKET)/jar/automation-groupware.jar \
	  --profile $(PROFILE)
	aws lambda update-function-code \
	  --function-name automation-groupware \
	  --s3-bucket $(S3_BUCKET) \
	  --s3-key jar/automation-groupware.jar \
	  --region $(REGION) \
	  --profile $(PROFILE)
	@echo "✅ groupware Lambda 배포 완료"

# ─────────────────────────────────────────────────────────────
# 전체 배포
# ─────────────────────────────────────────────────────────────

.PHONY: deploy-all
deploy-all: deploy-ingest deploy-worker deploy-scheduler deploy-groupware push-bot
	@echo ""
	@echo "✅ 전체 배포 완료"

# ─────────────────────────────────────────────────────────────
# 도움말
# ─────────────────────────────────────────────────────────────

.PHONY: help
help:
	@echo ""
	@echo "사용법: make [target]"
	@echo ""
	@echo "  [빌드]"
	@echo "  build              전체 Java shadowJar 빌드"
	@echo "  build-ingest       ingest Lambda 빌드"
	@echo "  build-worker       worker Lambda 빌드"
	@echo "  build-scheduler    scheduler Lambda 빌드"
	@echo "  build-groupware    groupware Lambda 빌드"
	@echo "  build-bot          groupware-bot Docker 이미지 빌드"
	@echo "  clean              빌드 아티팩트 정리"
	@echo ""
	@echo "  [배포]"
	@echo "  deploy-ingest      ingest Lambda 빌드 + S3 업로드 + Lambda 배포 (AutomationWebhookIngest)"
	@echo "  deploy-worker      worker Lambda 빌드 + S3 업로드 + Lambda 배포"
	@echo "  deploy-scheduler   scheduler Lambda 빌드 + S3 업로드 + Lambda 배포"
	@echo "  deploy-groupware   groupware Lambda 빌드 + S3 업로드 + Lambda 배포"
	@echo "  push-bot           groupware-bot Docker 이미지 빌드 + ECR 푸시"
	@echo "  deploy-all         전체 배포 (Lambda 4개 + Docker)"
	@echo ""
	@echo "  [설정값]"
	@echo "  ACCOUNT_ID  = $(ACCOUNT_ID)"
	@echo "  REGION      = $(REGION)"
	@echo "  PROFILE     = $(PROFILE)"
	@echo "  S3_BUCKET   = $(S3_BUCKET)"
	@echo "  ECR_REPO    = $(ECR_REPO)"
	@echo ""

.DEFAULT_GOAL := help
