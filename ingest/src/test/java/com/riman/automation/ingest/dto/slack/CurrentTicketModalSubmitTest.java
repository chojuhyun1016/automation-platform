package com.riman.automation.ingest.dto.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentTicketModalSubmitTest {

    /**
     * view_submission payload를 URL 인코딩하여 parse() 호출 가능한 body 생성
     */
    private static String buildPayloadBody(String period) {
        String json = """
                {
                  "type": "view_submission",
                  "user": { "id": "U1234" },
                  "view": {
                    "private_metadata": "U1234",
                    "state": {
                      "values": {
                        "block_ticket_period": {
                          "action_ticket_period": {
                            "selected_option": {
                              "value": "%s"
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(period);
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @CsvSource({
            "daily,    true,  false, false, false",
            "weekly,   false, true,  false, false",
            "monthly,  false, false, true,  false",
            "quarterly,false, false, false, true"
    })
    @DisplayName("period별 is*() 메서드가 올바르게 반환")
    void periodMethods_returnCorrectly(String period,
                                       boolean isDaily, boolean isWeekly,
                                       boolean isMonthly, boolean isQuarterly) throws Exception {
        CurrentTicketModalSubmit submit = CurrentTicketModalSubmit.parse(buildPayloadBody(period));

        assertThat(submit.isDaily()).isEqualTo(isDaily);
        assertThat(submit.isWeekly()).isEqualTo(isWeekly);
        assertThat(submit.isMonthly()).isEqualTo(isMonthly);
        assertThat(submit.isQuarterly()).isEqualTo(isQuarterly);
    }

    @Test
    @DisplayName("parse — userId와 type 파싱 확인")
    void parse_basicFields() throws Exception {
        CurrentTicketModalSubmit submit = CurrentTicketModalSubmit.parse(buildPayloadBody("monthly"));

        assertThat(submit.getUserId()).isEqualTo("U1234");
        assertThat(submit.isViewSubmission()).isTrue();
        assertThat(submit.getPeriod()).isEqualTo("monthly");
    }
}
