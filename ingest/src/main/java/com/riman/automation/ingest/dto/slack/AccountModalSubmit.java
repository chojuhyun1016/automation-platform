package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 계정관리 Modal Submit 페이로드 파싱 결과 VO.
 * callback_id 는 {@code account_manage_submit} 이며 그룹웨어 ID와 비밀번호 2개 필드로 구성된다.
 * action(register/update/delete)은 private_metadata 에 "userId|userName|action" 형식으로 저장된다.
 */
@Getter
public class AccountModalSubmit {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String userName;
  private final String groupwareId;
  private final String groupwarePassword;
  private final String action;

  private AccountModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    // private_metadata 형식: "userId|userName|action".
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

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
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
