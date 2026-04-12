package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyReportConfigTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Nested
    @DisplayName("isSectionEnabled")
    class IsSectionEnabledTest {

        @Test
        @DisplayName("member_overrides에 섹션이 명시되면 해당 설정을 사용한다")
        void memberOverride_explicitSections_usesOverride() {
            DailyReportConfig config = new DailyReportConfig();
            MemberReportPreference pref = new MemberReportPreference();
            pref.setSections(List.of("tickets", "links"));
            config.setMemberOverrides(Map.of("조주현", pref));
            config.setDefaultSections(List.of("announcements", "tickets", "links"));

            assertThat(config.isSectionEnabled("조주현", "tickets")).isTrue();
            assertThat(config.isSectionEnabled("조주현", "links")).isTrue();
            assertThat(config.isSectionEnabled("조주현", "announcements")).isFalse();
        }

        @Test
        @DisplayName("member_overrides에 없는 팀원은 default_sections를 사용한다")
        void unknownMember_usesDefaultSections() {
            DailyReportConfig config = new DailyReportConfig();
            config.setDefaultSections(List.of("tickets", "absences"));

            assertThat(config.isSectionEnabled("김진욱", "tickets")).isTrue();
            assertThat(config.isSectionEnabled("김진욱", "links")).isFalse();
        }

        @Test
        @DisplayName("member_overrides의 sections가 null이면 default_sections를 사용한다")
        void memberOverride_nullSections_fallsBackToDefault() {
            DailyReportConfig config = new DailyReportConfig();
            MemberReportPreference pref = new MemberReportPreference();
            pref.setSections(null);
            config.setMemberOverrides(Map.of("조주현", pref));
            config.setDefaultSections(List.of("tickets"));

            assertThat(config.isSectionEnabled("조주현", "tickets")).isTrue();
            assertThat(config.isSectionEnabled("조주현", "links")).isFalse();
        }

        @Test
        @DisplayName("default_sections도 null이면 모든 섹션이 활성화된다")
        void noDefaultSections_allEnabled() {
            DailyReportConfig config = new DailyReportConfig();

            assertThat(config.isSectionEnabled("조주현", "tickets")).isTrue();
            assertThat(config.isSectionEnabled("조주현", "announcements")).isTrue();
            assertThat(config.isSectionEnabled(null, "links")).isTrue();
        }
    }

    @Nested
    @DisplayName("getEffectiveJiraProjectKeys")
    class GetEffectiveJiraProjectKeysTest {

        @Test
        @DisplayName("member_overrides에 jira_project_keys가 있으면 해당 값을 사용한다")
        void memberOverride_hasProjectKeys_usesOverride() {
            DailyReportConfig config = new DailyReportConfig();
            config.setJiraProjectKeys(List.of("CCE", "RBO", "ABO"));

            MemberReportPreference pref = new MemberReportPreference();
            pref.setJiraProjectKeys(List.of("CCE"));
            config.setMemberOverrides(Map.of("김진욱", pref));

            assertThat(config.getEffectiveJiraProjectKeys("김진욱"))
                    .containsExactly("CCE");
        }

        @Test
        @DisplayName("member_overrides에 없는 팀원은 전역 jira_project_keys를 사용한다")
        void unknownMember_usesGlobalKeys() {
            DailyReportConfig config = new DailyReportConfig();
            config.setJiraProjectKeys(List.of("CCE", "RBO", "ABO"));

            assertThat(config.getEffectiveJiraProjectKeys("이태우"))
                    .containsExactly("CCE", "RBO", "ABO");
        }

        @Test
        @DisplayName("member_overrides의 jira_project_keys가 빈 리스트이면 전역 키를 사용한다")
        void memberOverride_emptyKeys_fallsBackToGlobal() {
            DailyReportConfig config = new DailyReportConfig();
            config.setJiraProjectKeys(List.of("CCE", "RBO"));

            MemberReportPreference pref = new MemberReportPreference();
            pref.setJiraProjectKeys(List.of());
            config.setMemberOverrides(Map.of("김진욱", pref));

            assertThat(config.getEffectiveJiraProjectKeys("김진욱"))
                    .containsExactly("CCE", "RBO");
        }
    }

    @Nested
    @DisplayName("JSON 파싱")
    class JsonParsingTest {

        @Test
        @DisplayName("member_overrides가 포함된 JSON을 정상 파싱한다")
        void parsesJsonWithMemberOverrides() throws Exception {
            String json = """
                    {
                      "enabled": true,
                      "report_channel_id": "C123",
                      "calendar_id": "cal@group",
                      "ticket_calendar_id": "ticket-cal@group",
                      "jira_project_keys": ["CCE", "RBO"],
                      "default_sections": ["tickets", "absences", "links"],
                      "member_overrides": {
                        "조주현": {
                          "sections": ["tickets", "team_tickets", "links"],
                          "jira_project_keys": ["CCE"]
                        }
                      }
                    }
                    """;

            DailyReportConfig config = OM.readValue(json, DailyReportConfig.class);

            assertThat(config.getEnabled()).isTrue();
            assertThat(config.getDefaultSections()).containsExactly("tickets", "absences", "links");
            assertThat(config.getMemberOverrides()).containsKey("조주현");
            assertThat(config.getMemberOverrides().get("조주현").getSections())
                    .containsExactly("tickets", "team_tickets", "links");
            assertThat(config.getMemberOverrides().get("조주현").getJiraProjectKeys())
                    .containsExactly("CCE");
        }

        @Test
        @DisplayName("member_overrides가 없는 기존 JSON도 정상 파싱한다 (하위 호환)")
        void parsesLegacyJsonWithoutOverrides() throws Exception {
            String json = """
                    {
                      "enabled": true,
                      "report_channel_id": "C123",
                      "calendar_id": "cal@group",
                      "ticket_calendar_id": "ticket-cal@group",
                      "jira_project_keys": ["CCE"]
                    }
                    """;

            DailyReportConfig config = OM.readValue(json, DailyReportConfig.class);

            assertThat(config.getMemberOverrides()).isNull();
            assertThat(config.getDefaultSections()).isNull();
            assertThat(config.isSectionEnabled("조주현", "tickets")).isTrue();
        }
    }
}
