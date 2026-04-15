package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.code.AbsenceTypeCode;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack 부재등록 Modal Submit 페이로드 파싱 결과 VO.
 * 모달은 부재 유형, 신청/취소 구분, 시작일, 종료일, 사유 5개 필드로 구성된다.
 */
@Getter
public class AbsenceModalSubmit {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String type;
  private final String userId;
  private final String userName;
  private final String absenceType;
  private final String action;
  private final String startDate;
  private final String endDate;
  private final String reason;

  private AbsenceModalSubmit(JsonNode payload) {
    this.type = payload.path("type").asText("");
    this.userId = payload.path("user").path("id").asText("");

    // private_metadata 형식: "userId|userName".
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

  /**
   * URL-encoded payload body를 파싱하여 요청 객체를 생성한다.
   */
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
  }

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
