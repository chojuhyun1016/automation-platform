package com.riman.automation.scheduler.service.load;

import com.riman.automation.common.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 보고서 규칙 파일 로더
 *
 * <p>S3에 저장된 규칙 파일을 읽어 AnthropicClient의 system 프롬프트로 주입한다.
 *
 * <p><b>S3 규칙 파일 위치:</b>
 * <pre>
 *   {CONFIG_BUCKET}/rules/
 *       DAILY_REPORT_RULES.md    ← 일일 보고서 규칙
 *       WEEKLY_REPORT_RULES.md   ← 주간 보고서 규칙
 * </pre>
 *
 * <p><b>규칙 수정:</b> S3 파일만 교체하면 됨 — 코드 배포 불필요.
 * Lambda cold start 시 갱신, warm 상태에서는 메모리 캐시 사용.
 *
 * <p><b>S3 파일이 없으면 {@link ConfigException} 을 던진다.</b>
 * 규칙 파일은 AI 후처리의 핵심 입력이므로 fallback 없이 즉시 실패하는 것이 올바른 동작.
 * 파일이 없는 경우 S3에 업로드한 뒤 재시도해야 한다.
 */
@Slf4j
public class ReportRulesService {

    private final S3Client s3Client;
    private final String bucket;

    /**
     * key(S3 경로) → 규칙 파일 내용 캐시 (Lambda warm 재사용)
     */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public ReportRulesService(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /**
     * 일일 보고서 규칙 반환. S3 파일이 없으면 ConfigException 발생.
     */
    public String loadDailyRules() {
        return load("rules/DAILY_REPORT_RULES.md");
    }

    /**
     * 주간 보고서 규칙 반환. S3 파일이 없으면 ConfigException 발생.
     */
    public String loadWeeklyRules() {
        return load("rules/WEEKLY_REPORT_RULES.md");
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private String load(String s3Key) {
        return cache.computeIfAbsent(s3Key, key -> {
            try {
                log.info("[ReportRulesService] S3 규칙 파일 로드: {}/{}", bucket, key);
                byte[] bytes = s3Client.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(key).build()
                ).readAllBytes();
                String content = new String(bytes, StandardCharsets.UTF_8);
                log.info("[ReportRulesService] 로드 완료: {} chars", content.length());
                return content;
            } catch (Exception e) {
                // 규칙 파일 없음 = 설정 누락. fallback 없이 즉시 실패.
                throw new ConfigException(
                        "보고서 규칙 파일 S3 로드 실패: " + bucket + "/" + key
                                + "  →  aws s3 cp <파일> s3://" + bucket + "/" + key + " 로 업로드 필요", e);
            }
        });
    }
}
