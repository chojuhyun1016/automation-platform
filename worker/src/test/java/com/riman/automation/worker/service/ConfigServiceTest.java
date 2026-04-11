package com.riman.automation.worker.service;

import com.riman.automation.common.exception.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String CONFIG_KEY = "config.json";

    @Mock
    private S3Client s3Client;

    private void stubS3Config(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                new ByteArrayInputStream(bytes));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    }

    // =========================================================================
    // 기본 설정 로드
    // =========================================================================

    @Nested
    @DisplayName("설정 로드")
    class LoadConfigTest {

        @Test
        @DisplayName("S3에서 config.json을 로드하고 라우팅 설정을 반환한다")
        void loadConfig_returnsRoutingConfig() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            ConfigService.ProjectRouting routing = service.getRoutingConfig("CCE");
            assertThat(routing).isNotNull();
            assertThat(routing.getSlackChannelId()).isEqualTo("C-CCE");
            assertThat(routing.getCalendarId()).isEqualTo("cal-cce@group.calendar.google.com");
        }

        @Test
        @DisplayName("존재하지 않는 프로젝트 키면 null 반환")
        void getRoutingConfig_unknownKey_returnsNull() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getRoutingConfig("UNKNOWN")).isNull();
        }

        @Test
        @DisplayName("S3 로드 실패 시 ConfigException 발생")
        void loadConfig_s3Failure_throwsConfigException() {
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(new RuntimeException("S3 error"));

            assertThatThrownBy(() -> new ConfigService(s3Client, BUCKET, CONFIG_KEY))
                    .isInstanceOf(ConfigException.class);
        }
    }

    // =========================================================================
    // 캘린더 ID 폴백 체인
    // =========================================================================

    @Nested
    @DisplayName("getAbsenceCalendarId — 폴백 체인")
    class AbsenceCalendarIdTest {

        @Test
        @DisplayName("absence.calendar_id가 있으면 그것을 반환")
        void absenceCalendarId_absenceConfigSet_returnsAbsenceCalendarId() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getAbsenceCalendarId()).isEqualTo("cal-absence@group.calendar.google.com");
        }

        @Test
        @DisplayName("absence.calendar_id가 없으면 remoteWork.calendar_id로 폴백")
        void absenceCalendarId_noAbsence_fallsBackToRemoteWork() {
            stubS3Config(configJsonWithoutAbsence());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getAbsenceCalendarId()).isEqualTo("cal-remote@group.calendar.google.com");
        }

        @Test
        @DisplayName("둘 다 없으면 CCE calendar_id로 폴백")
        void absenceCalendarId_noAbsenceNoRemote_fallsBackToCCE() {
            stubS3Config(configJsonMinimal());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getAbsenceCalendarId()).isEqualTo("cal-cce@group.calendar.google.com");
        }
    }

    @Nested
    @DisplayName("getRemoteWorkCalendarId — 폴백 체인")
    class RemoteWorkCalendarIdTest {

        @Test
        @DisplayName("remoteWork.calendar_id가 있으면 그것을 반환")
        void remoteWorkCalendarId_set_returnsRemoteWorkCalendarId() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getRemoteWorkCalendarId()).isEqualTo("cal-remote@group.calendar.google.com");
        }

        @Test
        @DisplayName("remoteWork.calendar_id가 없으면 CCE calendar_id로 폴백")
        void remoteWorkCalendarId_noRemote_fallsBackToCCE() {
            stubS3Config(configJsonMinimal());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getRemoteWorkCalendarId()).isEqualTo("cal-cce@group.calendar.google.com");
        }
    }

    @Nested
    @DisplayName("getScheduleCalendarId — 폴백 체인")
    class ScheduleCalendarIdTest {

        @Test
        @DisplayName("absence → remoteWork → CCE 순 폴백")
        void scheduleCalendarId_fullConfig_returnsAbsenceCalendarId() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);

            assertThat(service.getScheduleCalendarId()).isEqualTo("cal-absence@group.calendar.google.com");
        }
    }

    // =========================================================================
    // TTL 캐시
    // =========================================================================

    @Nested
    @DisplayName("TTL 캐시 동작")
    class CacheTest {

        @Test
        @DisplayName("5분 이내 재조회 시 S3를 다시 호출하지 않는다")
        void cache_withinTtl_noReload() {
            stubS3Config(fullConfigJson());

            ConfigService service = new ConfigService(s3Client, BUCKET, CONFIG_KEY);
            // 생성자에서 1회 로드 + 이후 조회
            service.getRoutingConfig("CCE");
            service.getAbsenceCalendarId();
            service.getRemoteWorkCalendarId();

            // 생성자에서 1회만 호출
            verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
        }
    }

    // =========================================================================
    // JSON 헬퍼
    // =========================================================================

    private String fullConfigJson() {
        return """
                {
                  "version": "1.0",
                  "routing": {
                    "CCE": {
                      "slack_channel_id": "C-CCE",
                      "slack_bot_token_secret": "slack/bot-token",
                      "notification_enabled": true,
                      "send_to_channel": true,
                      "send_to_individuals": true,
                      "calendar_enabled": true,
                      "calendar_id": "cal-cce@group.calendar.google.com"
                    }
                  },
                  "defaultConfig": {
                    "slack_channel_id": "C-DEFAULT",
                    "calendar_id": "primary"
                  },
                  "remoteWork": {
                    "calendar_id": "cal-remote@group.calendar.google.com",
                    "notification_enabled": true,
                    "reminder_days_before": 1
                  },
                  "absence": {
                    "calendar_id": "cal-absence@group.calendar.google.com",
                    "notification_enabled": true
                  }
                }
                """;
    }

    private String configJsonWithoutAbsence() {
        return """
                {
                  "version": "1.0",
                  "routing": {
                    "CCE": {
                      "slack_channel_id": "C-CCE",
                      "calendar_id": "cal-cce@group.calendar.google.com"
                    }
                  },
                  "remoteWork": {
                    "calendar_id": "cal-remote@group.calendar.google.com"
                  }
                }
                """;
    }

    private String configJsonMinimal() {
        return """
                {
                  "version": "1.0",
                  "routing": {
                    "CCE": {
                      "slack_channel_id": "C-CCE",
                      "calendar_id": "cal-cce@group.calendar.google.com"
                    }
                  }
                }
                """;
    }
}
