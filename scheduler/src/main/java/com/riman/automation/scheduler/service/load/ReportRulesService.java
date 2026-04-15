package com.riman.automation.scheduler.service.load;

import com.riman.automation.common.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 보고서 규칙 파일 로더이다. S3의 rules/DAILY_REPORT_RULES.md, rules/WEEKLY_REPORT_RULES.md를 읽어
 * AnthropicClient의 system 프롬프트로 주입한다. Lambda warm 상태에서는 메모리 캐시를 재사용하여
 * 반복 로딩을 방지한다. 규칙 파일은 AI 후처리의 핵심 입력이므로 파일 누락 시 fallback 없이 ConfigException을 던진다.
 */
@Slf4j
public class ReportRulesService {

  private final S3Client s3Client;
  private final String bucket;

  /** S3 경로와 규칙 파일 내용 매핑 캐시이다. Lambda warm 재사용 시 중복 로드를 방지한다. */
  private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

  public ReportRulesService(S3Client s3Client, String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  /**
   * 일일 보고서 규칙을 반환한다. S3 파일이 없으면 ConfigException이 발생한다.
   */
  public String loadDailyRules() {
    return load("rules/DAILY_REPORT_RULES.md");
  }

  /**
   * 주간 보고서 규칙을 반환한다. S3 파일이 없으면 ConfigException이 발생한다.
   */
  public String loadWeeklyRules() {
    return load("rules/WEEKLY_REPORT_RULES.md");
  }

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
        throw new ConfigException(
            "보고서 규칙 파일 S3 로드 실패: " + bucket + "/" + key
                + "  →  aws s3 cp <파일> s3://" + bucket + "/" + key + " 로 업로드 필요", e);
      }
    });
  }
}
