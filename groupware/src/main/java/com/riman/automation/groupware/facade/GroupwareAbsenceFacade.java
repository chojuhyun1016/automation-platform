package com.riman.automation.groupware.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.groupware.dto.GroupwareAbsenceMessage;
import com.riman.automation.groupware.service.EcsTaskService;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 그룹웨어 부재 신청 오케스트레이터.
 *
 * <pre>
 * 1. groupware-config.json 로드 (S3)
 * 2. approval_rules에서 팀+역할 기반 결재자 결정
 * 3. ECS Fargate Task 환경변수 구성 (ID/PW 미포함)
 * 4. ECS Fargate Task 실행 (RunTask API)
 * 5. "처리 중" Slack DM 전송 (완료 결과는 Python Task에서 비동기 DM)
 *
 * cancel 액션은 그룹웨어 자동화 불가 → Slack 수동 처리 안내
 * </pre>
 */
@Slf4j
public class GroupwareAbsenceFacade {

    private static final ObjectMapper OM = new ObjectMapper();

    private final EcsTaskService ecsTaskService;
    private final SlackClient slackClient;
    private final S3Client s3Client;
    private final String configBucket;
    private final String groupwareConfigKey;
    private final String credentialsSecretName;
    private final String slackTokenSecretName;

    public GroupwareAbsenceFacade() {
        this.ecsTaskService = new EcsTaskService();
        this.slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
        this.s3Client = S3Client.builder().build();
        this.configBucket = System.getenv("CONFIG_BUCKET");
        this.groupwareConfigKey = System.getenv("GROUPWARE_CONFIG_KEY"); // "groupware-config.json"
        this.credentialsSecretName = System.getenv("GROUPWARE_CREDENTIALS_SECRET");
        this.slackTokenSecretName = System.getenv("SLACK_BOT_TOKEN_SECRET_NAME");
        log.info("[GroupwareAbsenceFacade] initialized: configBucket={}, configKey={}",
                configBucket, groupwareConfigKey);
    }

    public void handle(GroupwareAbsenceMessage msg) {
        // cancel은 그룹웨어 자동 취소 불가 → Slack 수동 처리 안내
        if (!msg.isApply()) {
            log.info("[GroupwareAbsenceFacade] cancel 액션 — 수동 처리 안내: user={}",
                    msg.getMemberName());
            notifyCancel(msg);
            return;
        }

        // 1. groupware-config.json 로드
        JsonNode config = loadGroupwareConfig();
        if (!config.path("groupware").path("enabled").asBoolean(true)) {
            log.info("[GroupwareAbsenceFacade] 그룹웨어 자동화 disabled (config)");
            return;
        }

        // 2. 결재자 결정 (approval_rules: 팀 → 역할 → approver)
        String approverName = resolveApproverName(msg, config);
        String approverKeyword = resolveApproverKeyword(msg, config, approverName);
        log.info("[GroupwareAbsenceFacade] 결재자 결정: user={}, team={}, role={}, approver={}",
                msg.getMemberName(), msg.getTeam(), msg.getRole(), approverName);

        // 3. ECS Task 환경변수 구성
        Map<String, String> taskEnv = buildTaskEnv(msg, config, approverName, approverKeyword);

        // 4. ECS Fargate Task 실행
        String taskArn;
        try {
            taskArn = ecsTaskService.runAbsenceTask(taskEnv);
            log.info("[GroupwareAbsenceFacade] ECS Task 실행 완료: taskArn={}", taskArn);
        } catch (Exception e) {
            log.error("[GroupwareAbsenceFacade] ECS Task 실행 실패", e);
            notifyFailure(msg, "내부 오류로 자동 신청에 실패했습니다.");
            throw new RuntimeException("ECS Task 실행 실패", e);
        }

        // 5. "처리 중" Slack DM (완료 결과는 Python Task에서 비동기 DM)
        notifyProcessing(msg, approverName);
    }

    // =========================================================================
    // 내부 — 결재자 결정
    // =========================================================================

    private String resolveApproverName(GroupwareAbsenceMessage msg, JsonNode config) {
        JsonNode rule = config.path("approval_rules")
                .path(msg.getTeam())
                .path(msg.getRole());
        if (!rule.isMissingNode()) {
            return rule.path("approver_name").asText("");
        }
        log.warn("[GroupwareAbsenceFacade] 결재자 미설정: team={}, role={}",
                msg.getTeam(), msg.getRole());
        return "";
    }

    private String resolveApproverKeyword(
            GroupwareAbsenceMessage msg, JsonNode config, String fallback) {
        JsonNode rule = config.path("approval_rules")
                .path(msg.getTeam())
                .path(msg.getRole());
        if (!rule.isMissingNode()) {
            String kw = rule.path("approver_search_keyword").asText("");
            return kw.isBlank() ? fallback : kw;
        }
        return fallback;
    }

    // =========================================================================
    // 내부 — Task 환경변수 구성
    // =========================================================================

    /**
     * Fargate Task에 주입할 환경변수 맵 구성.
     * ID/PW는 절대 포함하지 않으며, Task 내부에서 GROUPWARE_CREDENTIALS_SECRET으로 직접 조회.
     */
    private Map<String, String> buildTaskEnv(
            GroupwareAbsenceMessage msg,
            JsonNode config,
            String approverName,
            String approverKeyword) {

        JsonNode gw = config.path("groupware");
        Map<String, String> env = new HashMap<>();

        // 그룹웨어 접속 정보
        env.put("GW_LOGIN_URL", gw.path("login_url").asText());
        env.put("GW_BASE_URL", gw.path("base_url").asText());
        env.put("GW_TIMEOUT_SEC", gw.path("timeout_seconds").asText("120"));

        // 신청자 정보 (ID/PW 제외)
        env.put("SLACK_USER_ID", msg.getSlackUserId());
        env.put("MEMBER_NAME", msg.getMemberName());
        env.put("TEAM", msg.getTeam());
        env.put("ROLE", msg.getRole());

        // 부재 정보 — Slack 부재명 그대로 전달
        env.put("ABSENCE_TYPE", msg.getAbsenceType());
        env.put("START_DATE", msg.getStartDate());
        env.put("END_DATE", msg.getEndDate());
        env.put("REASON", msg.getReason() != null ? msg.getReason() : "개인사유");

        // 결재선
        env.put("APPROVER_NAME", approverName);
        env.put("APPROVER_KEYWORD", approverKeyword);

        // Secrets Manager 이름 (Python이 직접 조회)
        env.put("GROUPWARE_CREDENTIALS_SECRET", credentialsSecretName);
        env.put("SLACK_BOT_TOKEN_SECRET_NAME", slackTokenSecretName);
        env.put("SLACK_USER_ID_FOR_DM", msg.getSlackUserId());

        // 스크린샷 S3
        env.put("SCREENSHOT_BUCKET", gw.path("screenshot_bucket").asText());
        env.put("SCREENSHOT_PREFIX", gw.path("screenshot_prefix").asText("groupware-screenshots/"));

        return env;
    }

    // =========================================================================
    // 내부 — S3 설정 로드
    // =========================================================================

    private JsonNode loadGroupwareConfig() {
        try {
            byte[] bytes = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(configBucket)
                            .key(groupwareConfigKey)
                            .build()
            ).readAllBytes();
            return OM.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ConfigException("groupware-config.json 로드 실패: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 내부 — Slack 알림
    // =========================================================================

    private void notifyProcessing(GroupwareAbsenceMessage msg, String approverName) {
        try {
            String ch = slackClient.openDm(msg.getSlackUserId());
            String text = String.format(
                    "⏳ *그룹웨어 부재 신청 처리 중*\n"
                            + "> 부재명: %s\n"
                            + "> 기간: %s ~ %s\n"
                            + "> 결재자: %s\n"
                            + "> 완료 시 별도 안내 드립니다.",
                    msg.getAbsenceType(), msg.getStartDate(),
                    msg.getEndDate(), approverName);
            slackClient.postMessage(
                    SlackBlockBuilder.forChannel(ch)
                            .fallbackText("그룹웨어 부재 신청 처리 중")
                            .section(text)
                            .build());
        } catch (Exception e) {
            log.warn("[GroupwareAbsenceFacade] 처리중 DM 실패(무시): {}", e.getMessage());
        }
    }

    private void notifyFailure(GroupwareAbsenceMessage msg, String reason) {
        try {
            String ch = slackClient.openDm(msg.getSlackUserId());
            String text = String.format(
                    "❌ *그룹웨어 부재 신청 실패*\n"
                            + "> 부재명: %s / 기간: %s ~ %s\n"
                            + "> 사유: %s\n"
                            + "> 그룹웨어에서 직접 신청해 주세요: <https://gw.riman.com>",
                    msg.getAbsenceType(), msg.getStartDate(),
                    msg.getEndDate(), reason);
            slackClient.postMessage(
                    SlackBlockBuilder.forChannel(ch)
                            .fallbackText("그룹웨어 부재 신청 실패")
                            .section(text)
                            .build());
        } catch (Exception e) {
            log.warn("[GroupwareAbsenceFacade] 실패 DM 실패(무시): {}", e.getMessage());
        }
    }

    private void notifyCancel(GroupwareAbsenceMessage msg) {
        try {
            String ch = slackClient.openDm(msg.getSlackUserId());
            String text = "ℹ️ *그룹웨어 부재 취소 안내*\n"
                    + "> 구글 캘린더 부재는 자동 취소되었습니다.\n"
                    + "> 그룹웨어 결재 취소는 직접 진행해 주세요.\n"
                    + "> <https://gw.riman.com|그룹웨어 바로가기>";
            slackClient.postMessage(
                    SlackBlockBuilder.forChannel(ch)
                            .fallbackText("그룹웨어 부재 취소 안내")
                            .section(text)
                            .build());
        } catch (Exception e) {
            log.warn("[GroupwareAbsenceFacade] 취소 DM 실패(무시): {}", e.getMessage());
        }
    }
}
