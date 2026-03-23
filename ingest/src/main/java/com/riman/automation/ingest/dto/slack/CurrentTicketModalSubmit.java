package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * /현재티켓 Modal Submit 페이로드 파싱 결과 VO
 *
 * <p>모달 callback_id: {@code current_ticket_submit}
 *
 * <p>모달 block 구성:
 * <pre>
 *   block_ticket_period / action_ticket_period → 조회 기간 ("daily" | "weekly" | "quarterly")
 * </pre>
 *
 * <p>private_metadata 구조: "userId" (1단 — 요청자 본인 ID)
 *
 * <p>{@link RemoteWorkModalSubmit}, {@link AbsenceModalSubmit} 등과 동일한 네이밍 패턴을 따른다.
 */
@Getter
public class CurrentTicketModalSubmit {

    public static final String CALLBACK_ID = "current_ticket_submit";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String type;      // "view_submission"
    private final String userId;    // Slack User ID (payload.user.id)
    private final String period;    // "daily" | "weekly" | "quarterly"

    private CurrentTicketModalSubmit(JsonNode payload) {
        this.type = payload.path("type").asText("");
        this.userId = payload.path("user").path("id").asText("");

        // private_metadata: "userId" (1단)
        // userId는 payload.user.id 와 동일하므로 파싱 후 fallback으로 활용
        String meta = payload.path("view").path("private_metadata").asText("");
        // meta가 비어있으면 payload.user.id 사용 (안전 처리)
        // (private_metadata에 userId를 저장한 이유: 추후 확장 대비)

        this.period = payload
                .path("view").path("state").path("values")
                .path("block_ticket_period").path("action_ticket_period")
                .path("selected_option").path("value").asText("quarterly");
    }

    public static CurrentTicketModalSubmit parse(String urlEncodedBody) throws Exception {
        String decoded = URLDecoder.decode(
                urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
        return new CurrentTicketModalSubmit(OBJECT_MAPPER.readTree(decoded));
    }

    public boolean isViewSubmission() {
        return "view_submission".equals(type);
    }

    /**
     * 일별 조회 여부
     */
    public boolean isDaily() {
        return "daily".equals(period);
    }

    /**
     * 주별 조회 여부
     */
    public boolean isWeekly() {
        return "weekly".equals(period);
    }

    /**
     * 분기별 조회 여부
     */
    public boolean isQuarterly() {
        return "quarterly".equals(period);
    }
}
