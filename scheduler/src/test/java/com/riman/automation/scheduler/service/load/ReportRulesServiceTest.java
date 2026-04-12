package com.riman.automation.scheduler.service.load;

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
class ReportRulesServiceTest {

    @Mock
    private S3Client s3Client;

    private ReportRulesService service;

    private static final String BUCKET = "test-bucket";

    @BeforeEach
    void setUp() {
        service = new ReportRulesService(s3Client, BUCKET);
    }

    // =========================================================================
    // loadDailyRules
    // =========================================================================

    @Nested
    @DisplayName("loadDailyRules")
    class LoadDailyRulesTest {

        @Test
        @DisplayName("S3 로드 성공 → 규칙 파일 내용 반환")
        void success() {
            String content = "# Daily Report Rules\n이것은 규칙입니다.";
            mockS3Response(content);

            String result = service.loadDailyRules();

            assertThat(result).isEqualTo(content);
            verify(s3Client).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("캐시 히트 → S3 미호출")
        void cacheHit() {
            String content = "# Rules";
            mockS3Response(content);

            service.loadDailyRules(); // 첫 호출 — S3
            service.loadDailyRules(); // 두 번째 — 캐시

            verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("S3 실패 → ConfigException")
        void s3Failure() {
            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(new RuntimeException("NoSuchKey"));

            assertThatThrownBy(() -> service.loadDailyRules())
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("규칙 파일 S3 로드 실패");
        }
    }

    // =========================================================================
    // loadWeeklyRules
    // =========================================================================

    @Nested
    @DisplayName("loadWeeklyRules")
    class LoadWeeklyRulesTest {

        @Test
        @DisplayName("S3 로드 성공")
        void success() {
            String content = "# Weekly Report Rules";
            mockS3Response(content);

            String result = service.loadWeeklyRules();

            assertThat(result).isEqualTo(content);
        }
    }

    // =========================================================================
    // 캐시 독립성
    // =========================================================================

    @Nested
    @DisplayName("캐시")
    class CacheTest {

        @Test
        @DisplayName("daily와 weekly 규칙이 독립 캐시")
        void independentCache() {
            String dailyContent = "daily rules";
            String weeklyContent = "weekly rules";

            // daily 호출 시
            mockS3Response(dailyContent);
            service.loadDailyRules();

            // weekly 호출 시 — 별도 S3 요청
            reset(s3Client);
            mockS3Response(weeklyContent);
            service.loadWeeklyRules();

            verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
        }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void mockS3Response(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(bytes));

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);
    }
}
