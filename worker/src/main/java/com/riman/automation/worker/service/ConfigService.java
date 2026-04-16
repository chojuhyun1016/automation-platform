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
 * S3 기반 설정 서비스.
 * config.json을 S3에서 로드하여 5분 TTL로 캐싱하고 각 기능별 설정값을 제공한다.
 * 관리 섹션은 Jira 프로젝트 라우팅(ProjectRouting), 재택근무(RemoteWorkConfig), 부재등록(AbsenceConfig)이다.
 * 캘린더 ID는 absence → remoteWork → CCE(기본) → "primary" 순서로 폴백된다.
 */
@Slf4j
public class ConfigService {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final long CACHE_TTL_MS = 5 * 60 * 1000;

  private final S3Client s3Client;
  private final String bucketName;
  private final String configKey;

  private Map<String, ProjectRouting> routingConfigs;
  private ProjectRouting defaultConfig;
  private RemoteWorkConfig remoteWorkConfig;
  private AbsenceConfig absenceConfig;
  private LunchCardConfig lunchCardConfig;
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

  /**
   * 테스트용 생성자. Mock S3Client를 주입할 수 있으며 생성 시 loadConfig()가 호출된다.
   */
  ConfigService(S3Client s3Client, String bucketName, String configKey) {
    this.s3Client = s3Client;
    this.bucketName = bucketName;
    this.configKey = configKey;
    loadConfig();
  }

  /**
   * 프로젝트 키 기반 Jira 라우팅 설정을 반환한다. 없으면 null을 반환한다.
   *
   * @param projectKey Jira 프로젝트 키 (예: CCE, RBO)
   */
  public ProjectRouting getRoutingConfig(String projectKey) {
    refreshIfExpired();
    return routingConfigs.getOrDefault(projectKey, null);
  }

  /**
   * config.json defaultConfig 섹션의 기본 라우팅 설정을 반환한다.
   */
  public ProjectRouting getDefaultRoutingConfig() {
    refreshIfExpired();
    return defaultConfig;
  }

  /**
   * 재택근무 캘린더 ID를 반환한다.
   * 폴백 순서: remoteWork.calendar_id → CCE.calendar_id → "primary".
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

  public RemoteWorkConfig getRemoteWorkConfig() {
    refreshIfExpired();
    return remoteWorkConfig;
  }

  /**
   * 부재등록 캘린더 ID를 반환한다.
   * 폴백 순서: absence.calendar_id → remoteWork.calendar_id → CCE.calendar_id → "primary".
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

  public AbsenceConfig getAbsenceConfig() {
    refreshIfExpired();
    return absenceConfig;
  }

  /**
   * 점심카드 캘린더 ID를 반환한다.
   * 폴백 순서: lunchCard.calendar_id → absence.calendar_id → remoteWork.calendar_id → CCE.calendar_id → "primary".
   */
  public String getLunchCardCalendarId() {
    refreshIfExpired();

    if (lunchCardConfig != null
        && lunchCardConfig.getCalendarId() != null
        && !lunchCardConfig.getCalendarId().isEmpty()) {
      log.info("점심카드 캘린더 ID: {}", lunchCardConfig.getCalendarId());
      return lunchCardConfig.getCalendarId();
    }

    log.warn("lunchCard calendar_id 미설정, absence 캘린더로 폴백");
    return getAbsenceCalendarId();
  }

  /**
   * 점심카드 알림 채널 ID를 반환한다.
   * lunchCard.notification_channel_id가 미설정이면 null을 반환한다.
   */
  public String getLunchCardNotificationChannelId() {
    refreshIfExpired();

    if (lunchCardConfig != null
        && lunchCardConfig.getNotificationChannelId() != null
        && !lunchCardConfig.getNotificationChannelId().isEmpty()) {
      return lunchCardConfig.getNotificationChannelId();
    }

    log.warn("lunchCard notification_channel_id 미설정");
    return null;
  }

  public LunchCardConfig getLunchCardConfig() {
    refreshIfExpired();
    return lunchCardConfig;
  }

  /**
   * 일정등록 캘린더 ID를 반환한다.
   * 별도 schedule 섹션 없이 absence → remoteWork → CCE → "primary" 폴백 체인을 재사용한다.
   * 일정은 부재/재택과 동일한 공유 캘린더에 등록하려는 의도이며, 향후 schedule 섹션이 추가되면 이 메서드만 수정한다.
   */
  public String getScheduleCalendarId() {
    refreshIfExpired();

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

  /**
   * TTL이 만료되었으면 S3에서 config를 재로드한다.
   */
  private void refreshIfExpired() {
    if (System.currentTimeMillis() - lastLoadTime > CACHE_TTL_MS) {
      log.info("Config 캐시 만료, 재로드");
      loadConfig();
    }
  }

  /**
   * CCE 라우팅의 calendar_id를 기본값으로 반환한다. 없으면 "primary"로 폴백한다.
   */
  private String getDefaultCalendarId() {
    if (routingConfigs != null && routingConfigs.containsKey("CCE")) {
      String cceCalendarId = routingConfigs.get("CCE").getCalendarId();
      log.info("CCE 캘린더 ID 사용: {}", cceCalendarId);
      return cceCalendarId;
    }
    log.warn("CCE 설정 없음, primary 사용");
    return "primary";
  }

  /**
   * S3에서 config.json을 로드하여 내부 필드를 갱신한다.
   * 최초 로드 실패 시 ConfigException을 던지고, 이후 로드 실패 시 기존 캐시를 유지한다.
   */
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
      this.lunchCardConfig = configFile.getLunchCard();
      this.lastLoadTime = System.currentTimeMillis();

      log.info("Config 로드 완료: {}개 프로젝트, remoteWork.calendarId={}, absence.calendarId={}, lunchCard.calendarId={}",
          routingConfigs.size(),
          remoteWorkConfig != null ? remoteWorkConfig.getCalendarId() : "null",
          absenceConfig != null ? absenceConfig.getCalendarId() : "null",
          lunchCardConfig != null ? lunchCardConfig.getCalendarId() : "null");

    } catch (Exception e) {
      log.error("Config 로드 실패", e);
      if (routingConfigs == null) {
        throw new ConfigException("초기 Config 로드 실패", e);
      }
      log.warn("캐시된 Config 유지");
    }
  }

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

    @JsonProperty("lunchCard")
    private LunchCardConfig lunchCard;
  }

  /**
   * Jira 프로젝트별 Slack/캘린더 라우팅 설정. config.json의 routing 섹션 값과 매핑된다.
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ProjectRouting {

    /** Slack 채널 ID. */
    @JsonProperty("slack_channel_id")
    private String slackChannelId;

    /** Secrets Manager에서 Bot 토큰을 조회할 시크릿 이름. */
    @JsonProperty("slack_bot_token_secret")
    private String slackBotTokenSecret;

    /** Slack 알림 활성 여부 (기본값: true). */
    @JsonProperty("notification_enabled")
    private Boolean notificationEnabled = true;

    /** 채널 전송 활성 여부 (기본값: false). */
    @JsonProperty("send_to_channel")
    private Boolean sendToChannel = false;

    /** 개인 DM 전송 활성 여부 (기본값: true). */
    @JsonProperty("send_to_individuals")
    private Boolean sendToIndividuals = true;

    /** Google Calendar 연동 활성 여부 (기본값: false). */
    @JsonProperty("calendar_enabled")
    private Boolean calendarEnabled = false;

    /** Google Calendar ID (기본값: "primary"). */
    @JsonProperty("calendar_id")
    private String calendarId = "primary";
  }

  /**
   * 재택근무 캘린더 및 알림 설정. config.json의 remoteWork 섹션과 매핑된다.
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

  /**
   * 부재등록 캘린더 및 알림 설정. config.json의 absence 섹션과 매핑된다.
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AbsenceConfig {

    @JsonProperty("calendar_id")
    private String calendarId;

    @JsonProperty("notification_enabled")
    private Boolean notificationEnabled = true;
  }

  /**
   * 점심카드 캘린더 및 알림 설정. config.json의 lunchCard 섹션과 매핑된다.
   */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LunchCardConfig {

    @JsonProperty("calendar_id")
    private String calendarId;

    @JsonProperty("notification_channel_id")
    private String notificationChannelId;
  }
}
