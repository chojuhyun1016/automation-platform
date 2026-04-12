package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveConfigTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("기본값: enabled=false, prefix=reports")
    void defaultValues() {
        ArchiveConfig config = new ArchiveConfig();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getPrefix()).isEqualTo("reports");
    }

    @Test
    @DisplayName("JSON 파싱 — enabled=true, 커스텀 prefix")
    void parsesJson() throws Exception {
        String json = """
                {
                  "enabled": true,
                  "prefix": "archive/reports"
                }
                """;

        ArchiveConfig config = OM.readValue(json, ArchiveConfig.class);

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getPrefix()).isEqualTo("archive/reports");
    }

    @Test
    @DisplayName("archive 섹션이 없는 기존 JSON도 기본값으로 동작")
    void defaultWhenMissing() throws Exception {
        String json = "{}";
        ArchiveConfig config = OM.readValue(json, ArchiveConfig.class);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getPrefix()).isEqualTo("reports");
    }
}
