package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.code.AbsenceTypeCode;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 부재등록 Modal Submit 페이로드 파싱 결과 VO
 *
 * <p>모달 block 구성:
 * <pre>
 *   block_absence_type  / action_absence_type  → AbsenceTypeCode.getLabel()
 *   block_action_type   / action_action_type   → "apply" | "cancel"
 *   block_start_date    / action_start_date    → 시작일 (필수)
 *   block_end_date      / action_end_date      → 종료일 (날짜 1개 유형은 worker에서 시작일로 처리)
 *   block_reason        / action_reason        → 사유 (optional, 공란이면 worker에서 "개인사유" 처리)
 * </pre>
 *
 * <p>기존 RemoteWorkModalSubmit(재택근무)와 완전 독립.
 */
@Getter
public class AbsenceModalSubmit {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String type;        // "view_submission"
    private final String userId;      // Slack User ID
    private final String userName;    // private_metadata에서 복원
    private final String absenceType; // AbsenceTypeCode.getLabel()
    private final String action;      // "apply" | "cancel"
    private final String startDate;   // yyyy-MM-dd
    private final String endDate;     // yyyy-MM-dd
    private final String reason;      // 사유 (빈 문자열 가능)

    private AbsenceModalSubmit(JsonNode payload) {
        this.type = payload.path("type").asText("");
        this.userId = payload.path("user").path("id").asText("");

        // private_metadata: "userId|userName"
        String meta = payload.path("view").path("private_metadata").asText("");
        String rawName = payload.path("user").path("username").asText("");
        this.userName = meta.contains("|") ? meta.split("\\|", 2)[1] : rawName;

        JsonNode values = payload.path("view").path("state").path("values");

        this.absenceType = values
                .path("block_absence_type").path("action_absence_type")
                .path("selected_option").path("value").asText("");

        this.action = values
                .path("block_action_type").path("action_action_type")
                .path("selected_option").path("value").asText("");

        this.startDate = values
                .path("block_start_date").path("action_start_date")
                .path("selected_date").asText("");

        this.endDate = values
                .path("block_end_date").path("action_end_date")
                .path("selected_date").asText("");

        this.reason = values
                .path("block_reason").path("action_reason")
                .path("value").asText("");
    }

    public static AbsenceModalSubmit parse(String urlEncodedBody) throws Exception {
        String decoded = URLDecoder.decode(
                urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
        return new AbsenceModalSubmit(OBJECT_MAPPER.readTree(decoded));
    }

    public boolean isViewSubmission() {
        return "view_submission".equals(type);
    }

    public boolean isValidAbsenceType() {
        return AbsenceTypeCode.isValid(absenceType);
    }   // ✏️ 변경

    public boolean isValidAction() {
        return "apply".equals(action) || "cancel".equals(action);
    }

    public boolean isApply() {
        return "apply".equals(action);
    }

    public boolean isCancel() {
        return "cancel".equals(action);
    }
}
