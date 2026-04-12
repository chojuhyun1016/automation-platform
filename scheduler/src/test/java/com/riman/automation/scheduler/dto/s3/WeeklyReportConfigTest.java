package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyReportConfigTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("project_groups가 포함된 JSON을 정상 파싱한다")
    void parsesJsonWithProjectGroups() throws Exception {
        String json = """
                {
                  "enabled": true,
                  "team_name": "보상코어 개발팀",
                  "ticket_calendar_id": "cal@group",
                  "confluence_base_url": "https://example.com",
                  "confluence_space_key": "IT",
                  "confluence_parent_page_id": "123",
                  "project_groups": [
                    { "name": "주문/수당", "categories": ["주문", "수당", "포인트"] },
                    { "name": "회원/ABO/RBO", "categories": ["회원", "ABO", "RBO"] }
                  ]
                }
                """;

        WeeklyReportConfig config = OM.readValue(json, WeeklyReportConfig.class);

        assertThat(config.isGroupSeparationEnabled()).isTrue();
        assertThat(config.getProjectGroups()).hasSize(2);
        assertThat(config.getProjectGroups().get(0).getName()).isEqualTo("주문/수당");
        assertThat(config.getProjectGroups().get(0).getCategories())
                .containsExactly("주문", "수당", "포인트");
    }

    @Test
    @DisplayName("project_groups가 없는 기존 JSON도 정상 파싱한다 (하위 호환)")
    void parsesLegacyJsonWithoutProjectGroups() throws Exception {
        String json = """
                {
                  "enabled": true,
                  "team_name": "보상코어 개발팀",
                  "ticket_calendar_id": "cal@group"
                }
                """;

        WeeklyReportConfig config = OM.readValue(json, WeeklyReportConfig.class);

        assertThat(config.isGroupSeparationEnabled()).isFalse();
        assertThat(config.getProjectGroups()).isNull();
    }
}
