package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 계정관리 Modal Submit 페이로드 파싱 결과 VO
 *
 * <p>모달 callback_id: {@code account_manage_submit}
 *
 * <p>모달 block 구성:
 * <pre>
 *   block_groupware_id       / action_groupware_id       → 그룹웨어 ID(사번) (plain_text_input)
 *   block_groupware_password / action_groupware_password → 비밀번호 (plain_text_input, 평문)
 * </pre>
 *
 * <p>action(register/update/delete)은 private_metadata에 저장:
 * <pre>format: "userId|userName|action"</pre>
 *
 * <p>기존 AbsenceModalSubmit(부재등록), RemoteWorkModalSubmit(재택근무)과 완전 독립.
 */
@Getter
public class AccountModalSubmit {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String type;              // "view_submission"
    private final String userId;            // Slack User ID
    private final String userName;          // private_metadata에서 복원
    private final String groupwareId;       // 그룹웨어 ID(사번)
    private final String groupwarePassword; // 비밀번호 (평문)
    private final String action;            // "register" | "update" | "delete"

    private AccountModalSubmit(JsonNode payload) {
        this.type = payload.path("type").asText("");
        this.userId = payload.path("user").path("id").asText("");

        // private_metadata: "userId|userName|action"
        String meta = payload.path("view").path("private_metadata").asText("");
        String[] parts = meta.split("\\|", 3);
        this.userName = parts.length >= 2 ? parts[1] : payload.path("user").path("username").asText("");
        this.action = parts.length >= 3 ? parts[2] : "register";

        JsonNode values = payload.path("view").path("state").path("values");

        this.groupwareId = values
                .path("block_groupware_id").path("action_groupware_id")
                .path("value").asText("").trim();

        this.groupwarePassword = values
                .path("block_groupware_password").path("action_groupware_password")
                .path("value").asText("").trim();
    }

    public static AccountModalSubmit parse(String urlEncodedBody) throws Exception {
        String decoded = URLDecoder.decode(
                urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
        return new AccountModalSubmit(OBJECT_MAPPER.readTree(decoded));
    }

    public boolean isViewSubmission() {
        return "view_submission".equals(type);
    }

    public boolean isRegister() {
        return "register".equals(action);
    }

    public boolean isDelete() {
        return "delete".equals(action);
    }

    public boolean hasGroupwareId() {
        return groupwareId != null && !groupwareId.isBlank();
    }

    public boolean hasGroupwarePassword() {
        return groupwarePassword != null && !groupwarePassword.isBlank();
    }
}
