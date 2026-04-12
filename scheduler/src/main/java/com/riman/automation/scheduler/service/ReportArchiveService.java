package com.riman.automation.scheduler.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 보고서 히스토리 S3 아카이빙 서비스
 *
 * <p>생성된 보고서를 S3에 저장하여 히스토리를 보존한다.
 *
 * <p><b>S3 키 패턴:</b>
 * <ul>
 *   <li>daily: {@code {prefix}/daily/{yyyy-MM-dd}/{memberName}.json}</li>
 *   <li>weekly: {@code {prefix}/weekly/{yyyy-MM-dd}/report.html}</li>
 *   <li>weekly (그룹): {@code {prefix}/weekly/{yyyy-MM-dd}/{groupName}.html}</li>
 *   <li>monthly: {@code {prefix}/monthly/{yyyy-MM}/report.html}</li>
 *   <li>monthly (그룹): {@code {prefix}/monthly/{yyyy-MM}/{groupName}.html}</li>
 * </ul>
 */
@Slf4j
public class ReportArchiveService {

    private final S3Client s3Client;
    private final String bucket;
    private final String prefix;

    public ReportArchiveService(S3Client s3Client, String bucket, String prefix) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    /**
     * 일일 보고서를 S3에 아카이브한다.
     */
    public void archiveDaily(LocalDate baseDate, String memberName, String jsonPayload) {
        String key = buildDailyKey(baseDate, memberName);
        putObject(key, jsonPayload, "application/json; charset=utf-8");
        log.info("[ReportArchive] daily 저장 완료: {}", key);
    }

    /**
     * 주간 보고서를 S3에 아카이브한다.
     */
    public void archiveWeekly(LocalDate weekStart, String htmlContent) {
        String key = buildWeeklyKey(weekStart);
        putObject(key, htmlContent, "text/html; charset=utf-8");
        log.info("[ReportArchive] weekly 저장 완료: {}", key);
    }

    /**
     * 주간 보고서 (그룹별)를 S3에 아카이브한다.
     */
    public void archiveWeeklyGroup(LocalDate weekStart, String groupName, String htmlContent) {
        String key = buildWeeklyGroupKey(weekStart, groupName);
        putObject(key, htmlContent, "text/html; charset=utf-8");
        log.info("[ReportArchive] weekly group 저장 완료: {}", key);
    }

    /**
     * 월간 보고서를 S3에 아카이브한다.
     */
    public void archiveMonthly(String yearMonth, String htmlContent) {
        String key = buildMonthlyKey(yearMonth);
        putObject(key, htmlContent, "text/html; charset=utf-8");
        log.info("[ReportArchive] monthly 저장 완료: {}", key);
    }

    /**
     * 월간 보고서 (그룹별)를 S3에 아카이브한다.
     */
    public void archiveMonthlyGroup(String yearMonth, String groupName, String htmlContent) {
        String key = buildMonthlyGroupKey(yearMonth, groupName);
        putObject(key, htmlContent, "text/html; charset=utf-8");
        log.info("[ReportArchive] monthly group 저장 완료: {}", key);
    }

    // =========================================================================
    // S3 키 빌더 — package-private for testing
    // =========================================================================

    String buildDailyKey(LocalDate baseDate, String memberName) {
        return prefix + "/daily/" + baseDate + "/" + memberName + ".json";
    }

    String buildWeeklyKey(LocalDate weekStart) {
        return prefix + "/weekly/" + weekStart + "/report.html";
    }

    String buildWeeklyGroupKey(LocalDate weekStart, String groupName) {
        return prefix + "/weekly/" + weekStart + "/" + groupName + ".html";
    }

    String buildMonthlyKey(String yearMonth) {
        return prefix + "/monthly/" + yearMonth + "/report.html";
    }

    String buildMonthlyGroupKey(String yearMonth, String groupName) {
        return prefix + "/monthly/" + yearMonth + "/" + groupName + ".html";
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private void putObject(String key, String content, String contentType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
    }
}
