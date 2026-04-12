package com.riman.automation.scheduler.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportArchiveServiceTest {

    @Mock
    private S3Client s3Client;

    @Nested
    @DisplayName("buildS3Key")
    class BuildS3KeyTest {

        @Test
        @DisplayName("daily 보고서 S3 키를 올바르게 생성한다")
        void dailyKey() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");

            String key = service.buildDailyKey(LocalDate.of(2026, 4, 12), "조주현");

            assertThat(key).isEqualTo("reports/daily/2026-04-12/조주현.json");
        }

        @Test
        @DisplayName("weekly 보고서 S3 키를 올바르게 생성한다")
        void weeklyKey() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");

            String key = service.buildWeeklyKey(LocalDate.of(2026, 4, 6));

            assertThat(key).isEqualTo("reports/weekly/2026-04-06/report.html");
        }

        @Test
        @DisplayName("monthly 보고서 S3 키를 올바르게 생성한다")
        void monthlyKey() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");

            String key = service.buildMonthlyKey("2026-03");

            assertThat(key).isEqualTo("reports/monthly/2026-03/report.html");
        }

        @Test
        @DisplayName("커스텀 prefix를 사용한다")
        void customPrefix() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "archive/v2");

            String key = service.buildDailyKey(LocalDate.of(2026, 1, 5), "김진욱");

            assertThat(key).isEqualTo("archive/v2/daily/2026-01-05/김진욱.json");
        }

        @Test
        @DisplayName("weekly 그룹별 보고서 S3 키를 올바르게 생성한다")
        void weeklyGroupKey() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");

            String key = service.buildWeeklyGroupKey(LocalDate.of(2026, 4, 6), "주문_수당");

            assertThat(key).isEqualTo("reports/weekly/2026-04-06/주문_수당.html");
        }
    }

    @Nested
    @DisplayName("archiveDaily")
    class ArchiveDailyTest {

        @Test
        @DisplayName("S3에 일일 보고서를 저장한다")
        void archivesDaily() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");
            String content = "{\"test\": true}";

            service.archiveDaily(LocalDate.of(2026, 4, 12), "조주현", content);

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));
            PutObjectRequest req = reqCaptor.getValue();
            assertThat(req.bucket()).isEqualTo("my-bucket");
            assertThat(req.key()).isEqualTo("reports/daily/2026-04-12/조주현.json");
            assertThat(req.contentType()).isEqualTo("application/json; charset=utf-8");
        }
    }

    @Nested
    @DisplayName("archiveWeekly")
    class ArchiveWeeklyTest {

        @Test
        @DisplayName("S3에 주간 보고서를 저장한다")
        void archivesWeekly() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");
            String html = "<html>weekly report</html>";

            service.archiveWeekly(LocalDate.of(2026, 4, 6), html);

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));
            PutObjectRequest req = reqCaptor.getValue();
            assertThat(req.key()).isEqualTo("reports/weekly/2026-04-06/report.html");
            assertThat(req.contentType()).isEqualTo("text/html; charset=utf-8");
        }
    }

    @Nested
    @DisplayName("archiveMonthly")
    class ArchiveMonthlyTest {

        @Test
        @DisplayName("S3에 월간 보고서를 저장한다")
        void archivesMonthly() {
            ReportArchiveService service = new ReportArchiveService(s3Client, "my-bucket", "reports");
            String html = "<html>monthly report</html>";

            service.archiveMonthly("2026-03", html);

            ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));
            PutObjectRequest req = reqCaptor.getValue();
            assertThat(req.key()).isEqualTo("reports/monthly/2026-03/report.html");
        }
    }
}
