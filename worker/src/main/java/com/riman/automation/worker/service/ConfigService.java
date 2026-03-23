package com.riman.automation.worker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.ConfigException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.Map;

/**
 * S3 기반 설정 서비스
 *
 * <p>config.json을 S3에서 로드하여 캐싱하고, 각 기능별 설정값을 제공한다.
 * 캐시 TTL은 5분이며, 만료 시 자동 재로드한다.
 *
 * <p><b>관리하는 설정 섹션:</b>
 * <ul>
 *   <li>{@link ProjectRouting}   — Jira 프로젝트별 Slack/캘린더 라우팅</li>
 *   <li>{@link RemoteWorkConfig} — 재택근무 캘린더 설정</li>
 *   <li>{@link AbsenceConfig}    — 부재등록 캘린더 설정</li>
 * </ul>
 *
 * <p>기존 {@code RoutingConfig}(별도 파일)는 이 클래스의 내부 static class
 * {@link ProjectRouting}으로 흡수. {@code AbsenceConfig}, {@code RemoteWorkConfig}와
 * 동일한 위치에 두어 설정 구조를 한 곳에서 파악할 수 있다.
 */
@Slf4j
public class ConfigService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5분

    private final S3Client s3Client;
    private final String bucketName;
    private final String configKey;

    private Map<String, ProjectRouting> routingConfigs;
    private ProjectRouting defaultConfig;
    private RemoteWorkConfig remoteWorkConfig;
    private AbsenceConfig absenceConfig;
    private long lastLoadTime = 0;

    public ConfigService() {
        this.s3Client = S3Client.builder().build();
        this.bucketName = System.getenv("CONFIG_BUCKET");
        this.configKey = System.getenv("CONFIG_KEY");

        if (bucketName == null || configKey == null) {
            throw new ConfigException("CONFIG_BUCKET 또는 CONFIG_KEY 미설정");
        }

        log.info("ConfigService 초기화: bucket={}, key={}", bucketName, configKey);
        loadConfig();
    }

    // =========================================================================
    // Jira 라우팅
    // =========================================================================

    /**
     * 프로젝트 키 기반 라우팅 설정 조회.
     *
     * @param projectKey Jira 프로젝트 키 (예: CCE, RBO)
     * @return 라우팅 설정 (없으면 null)
     */
    public ProjectRouting getRoutingConfig(String projectKey) {
        refreshIfExpired();
        return routingConfigs.getOrDefault(projectKey, null);
    }

    /**
     * 기본 라우팅 설정 반환 (config.json defaultConfig 섹션).
     */
    public ProjectRouting getDefaultRoutingConfig() {
        refreshIfExpired();
        return defaultConfig;
    }

    // =========================================================================
    // 재택 캘린더 ID (기존 — 변경 없음)
    // =========================================================================

    /**
     * 재택근무 캘린더 ID 반환.
     * config.json > remoteWork.calendar_id → CCE calendar_id → primary 순 폴백.
     */
    public String getRemoteWorkCalendarId() {
        refreshIfExpired();

        if (remoteWorkConfig != null
                && remoteWorkConfig.getCalendarId() != null
                && !remoteWorkConfig.getCalendarId().isEmpty()) {
            log.info("재택 캘린더 ID: {}", remoteWorkConfig.getCalendarId());
            return remoteWorkConfig.getCalendarId();
        }

        log.warn("remoteWork calendar_id 미설정, CCE 캘린더 사용");
        return getDefaultCalendarId();
    }

    /**
     * 재택 전체 설정 반환.
     */
    public RemoteWorkConfig getRemoteWorkConfig() {
        refreshIfExpired();
        return remoteWorkConfig;
    }

    // =========================================================================
    // 부재 캘린더 ID (기존 — 변경 없음)
    // =========================================================================

    /**
     * 부재등록 캘린더 ID 반환.
     * config.json > absence.calendar_id → remoteWork.calendar_id → CCE calendar_id → primary 순 폴백.
     */
    public String getAbsenceCalendarId() {
        refreshIfExpired();

        if (absenceConfig != null
                && absenceConfig.getCalendarId() != null
                && !absenceConfig.getCalendarId().isEmpty()) {
            log.info("부재 캘린더 ID: {}", absenceConfig.getCalendarId());
            return absenceConfig.getCalendarId();
        }

        log.warn("absence calendar_id 미설정, remoteWork 캘린더로 폴백");
        return getRemoteWorkCalendarId();
    }

    /**
     * 부재 전체 설정 반환.
     */
    public AbsenceConfig getAbsenceConfig() {
        refreshIfExpired();
        return absenceConfig;
    }

    // =========================================================================
    // 일정 캘린더 ID (신규 추가) ← 변경
    // =========================================================================

    /**
     * 일정등록 캘린더 ID 반환.
     *
     * <p>별도 schedule 섹션 없이 기존 폴백 체인을 재사용한다.
     * absence.calendar_id → remoteWork.calendar_id → CCE calendar_id → primary 순 폴백.
     *
     * <p>일정은 부재/재택과 동일한 공유 캘린더에 등록되도록 의도됨.
     * 향후 config.json에 schedule 섹션이 추가되면 이 메서드만 수정하면 된다.
     */
    public String getScheduleCalendarId() {
        refreshIfExpired();

        // absence.calendar_id → remoteWork.calendar_id → default 폴백 체인 재사용
        if (absenceConfig != null
                && absenceConfig.getCalendarId() != null
                && !absenceConfig.getCalendarId().isEmpty()) {
            log.info("일정 캘린더 ID (absence 폴백): {}", absenceConfig.getCalendarId());
            return absenceConfig.getCalendarId();
        }

        if (remoteWorkConfig != null
                && remoteWorkConfig.getCalendarId() != null
                && !remoteWorkConfig.getCalendarId().isEmpty()) {
            log.info("일정 캘린더 ID (remoteWork 폴백): {}", remoteWorkConfig.getCalendarId());
            return remoteWorkConfig.getCalendarId();
        }

        log.warn("일정 캘린더 ID 미설정, CCE 캘린더 폴백");
        return getDefaultCalendarId();
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private void refreshIfExpired() {
        if (System.currentTimeMillis() - lastLoadTime > CACHE_TTL_MS) {
            log.info("Config 캐시 만료, 재로드");
            loadConfig();
        }
    }

    private String getDefaultCalendarId() {
        if (routingConfigs != null && routingConfigs.containsKey("CCE")) {
            String cceCalendarId = routingConfigs.get("CCE").getCalendarId();
            log.info("CCE 캘린더 ID 사용: {}", cceCalendarId);
            return cceCalendarId;
        }
        log.warn("CCE 설정 없음, primary 사용");
        return "primary";
    }

    private void loadConfig() {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(configKey)
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            byte[] bytes = response.readAllBytes();

            ConfigFile configFile = objectMapper.readValue(bytes, ConfigFile.class);

            this.routingConfigs = configFile.getRouting();
            this.defaultConfig = configFile.getDefaultConfig();
            this.remoteWorkConfig = configFile.getRemoteWork();
            this.absenceConfig = configFile.getAbsence();
            this.lastLoadTime = System.currentTimeMillis();

            log.info("Config 로드 완료: {}개 프로젝트, remoteWork.calendarId={}, absence.calendarId={}",
                    routingConfigs.size(),
                    remoteWorkConfig != null ? remoteWorkConfig.getCalendarId() : "null",
                    absenceConfig != null ? absenceConfig.getCalendarId() : "null");

        } catch (Exception e) {
            log.error("Config 로드 실패", e);
            if (routingConfigs == null) {
                throw new ConfigException("초기 Config 로드 실패", e);
            }
            log.warn("캐시된 Config 유지");
        }
    }

    // =========================================================================
    // 내부 클래스 — S3 JSON 매핑
    // =========================================================================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ConfigFile {
        private String version;
        private Map<String, ProjectRouting> routing;
        private ProjectRouting defaultConfig;

        @JsonProperty("remoteWork")
        private RemoteWorkConfig remoteWork;

        @JsonProperty("absence")
        private AbsenceConfig absence;
    }

    // ── Jira 프로젝트별 라우팅 설정 ──────────────────────────────────────────
    //
    // 기존 별도 파일 RoutingConfig(worker.config)를 이 클래스로 흡수.
    // 이 설정은 config.json의 routing 섹션에만 사용되므로
    // ConfigService 내부에 두는 것이 응집도를 높인다.

    /**
     * Jira 프로젝트별 Slack/캘린더 라우팅 설정.
     *
     * <p>config.json {@code routing} 섹션의 프로젝트 키별 값과 매핑된다.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectRouting {

        /**
         * Slack 채널 ID
         */
        @JsonProperty("slack_channel_id")
        private String slackChannelId;

        /**
         * Secrets Manager에서 Bot 토큰을 조회할 시크릿 이름
         */
        @JsonProperty("slack_bot_token_secret")
        private String slackBotTokenSecret;

        /**
         * Slack 알림 활성 여부 (기본값: true)
         */
        @JsonProperty("notification_enabled")
        private Boolean notificationEnabled = true;

        /**
         * 채널 전송 활성 여부 (기본값: false)
         */
        @JsonProperty("send_to_channel")
        private Boolean sendToChannel = false;

        /**
         * 개인 DM 전송 활성 여부 (기본값: true)
         */
        @JsonProperty("send_to_individuals")
        private Boolean sendToIndividuals = true;

        /**
         * Google Calendar 연동 활성 여부 (기본값: false)
         */
        @JsonProperty("calendar_enabled")
        private Boolean calendarEnabled = false;

        /**
         * Google Calendar ID (기본값: primary)
         */
        @JsonProperty("calendar_id")
        private String calendarId = "primary";
    }

    // ── 재택근무 설정 ─────────────────────────────────────────────────────────

    /**
     * 재택근무 캘린더 및 알림 설정.
     * config.json {@code remoteWork} 섹션과 매핑된다.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemoteWorkConfig {

        @JsonProperty("calendar_id")
        private String calendarId;

        @JsonProperty("notification_enabled")
        private Boolean notificationEnabled;

        @JsonProperty("reminder_days_before")
        private Integer reminderDaysBefore;
    }

    // ── 부재등록 설정 ─────────────────────────────────────────────────────────

    /**
     * 부재등록 캘린더 및 알림 설정.
     * config.json {@code absence} 섹션과 매핑된다.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AbsenceConfig {

        @JsonProperty("calendar_id")
        private String calendarId;

        @JsonProperty("notification_enabled")
        private Boolean notificationEnabled = true;
    }
}
