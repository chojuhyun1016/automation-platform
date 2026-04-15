package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 보고서 히스토리 S3 아카이빙 설정이다. scheduler-config.json의 "archive" 섹션과 매핑된다.
 * S3 키 패턴은 {prefix}/daily/{yyyy-MM-dd}/{member-name}.json, {prefix}/weekly/{yyyy-MM-dd}/report.html,
 * {prefix}/monthly/{yyyy-MM}/report.html이며 버킷은 CONFIG_BUCKET 환경변수와 동일한 값을 사용한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveConfig {

  /** 아카이빙 활성화 여부이다. 기본값은 false이다. */
  private Boolean enabled = false;

  /** S3 키 접두사이다. 기본값은 "reports"이다. */
  private String prefix = "reports";

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }
}
