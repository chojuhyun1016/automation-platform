package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 보고서 히스토리 S3 아카이빙 설정
 *
 * <p>scheduler-config.json 의 {@code "archive"} 섹션과 매핑된다.
 *
 * <pre>
 * {
 *   "archive": {
 *     "enabled": true,
 *     "prefix": "reports"
 *   }
 * }
 * </pre>
 *
 * <p>S3 키 패턴:
 * <ul>
 *   <li>daily: {@code {prefix}/daily/{yyyy-MM-dd}/{member-name}.json}</li>
 *   <li>weekly: {@code {prefix}/weekly/{yyyy-MM-dd}/report.html}</li>
 *   <li>monthly: {@code {prefix}/monthly/{yyyy-MM}/report.html}</li>
 * </ul>
 *
 * <p>버킷은 CONFIG_BUCKET 환경변수와 동일한 버킷을 사용한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveConfig {

    /**
     * 아카이빙 활성화 여부. 기본: false
     */
    private Boolean enabled = false;

    /**
     * S3 키 접두사. 기본: "reports"
     */
    private String prefix = "reports";

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
