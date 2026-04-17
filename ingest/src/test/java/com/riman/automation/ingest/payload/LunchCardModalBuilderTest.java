package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.ingest.payload.LunchCardModalBuilder.Status;
import com.riman.automation.ingest.payload.LunchCardModalBuilder.ViewData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LunchCardModalBuilderTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static Map<String, List<String>> sampleDayMap() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put("월", List.of("홍길동"));
    map.put("화", List.of("김철수", "이영희"));
    map.put("수", List.of());
    map.put("목", List.of());
    map.put("금", List.of());
    return map;
  }

  private static ViewData dataWithStatus(Status status, String registeredUser) {
    return new ViewData(
        "testuser", "U001", "2026-04-20",
        status, registeredUser, 3, 10, sampleDayMap());
  }

  @Nested
  @DisplayName("build (views.open)")
  class Build {

    @Test
    @DisplayName("기본 구조 — trigger_id, modal type, callback_id, private_metadata")
    void build_basicStructure() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      JsonNode root = OM.readTree(json);
      assertThat(root.path("trigger_id").asText()).isEqualTo("T123");

      JsonNode view = root.path("view");
      assertThat(view.path("type").asText()).isEqualTo("modal");
      assertThat(view.path("callback_id").asText()).isEqualTo("lunch_card_submit");
      assertThat(view.path("private_metadata").asText()).isEqualTo("U001|testuser");
    }

    @Test
    @DisplayName("datepicker 블록 — block_lunch_card_date / action_lunch_card_date")
    void build_hasDatepicker() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      JsonNode blocks = OM.readTree(json).path("view").path("blocks");
      JsonNode dateBlock = findBlockById(blocks, "block_lunch_card_date");

      assertThat(dateBlock).isNotNull();
      assertThat(dateBlock.path("element").path("action_id").asText())
          .isEqualTo("action_lunch_card_date");
      assertThat(dateBlock.path("element").path("initial_date").asText())
          .isEqualTo("2026-04-20");
      assertThat(dateBlock.path("dispatch_action").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("토글 블록 없음 — block_lunch_card_period 제거됨")
    void build_noPeriodToggle() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      JsonNode blocks = OM.readTree(json).path("view").path("blocks");
      JsonNode periodBlock = findBlockById(blocks, "block_lunch_card_period");

      assertThat(periodBlock).isNull();
    }

    @Test
    @DisplayName("카운트 표시 — 주간 + 월간 동시 표시")
    void build_hasCountDisplay() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      String jsonStr = OM.readTree(json).path("view").path("blocks").toString();
      assertThat(jsonStr).contains("주간 사용: *3*회");
      assertThat(jsonStr).contains("월간 사용: *10*회");
    }

    @Test
    @DisplayName("요일별 사용자 목록 — 월~금 표시")
    void build_hasDayOfWeekList() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      String jsonStr = OM.readTree(json).path("view").path("blocks").toString();
      assertThat(jsonStr).contains("홍길동");
      assertThat(jsonStr).contains("김철수");
    }
  }

  @Nested
  @DisplayName("상태별 UI 분기")
  class StatusBranching {

    @Test
    @DisplayName("미등록 — submit 버튼 존재 + 사용(apply) 기본 선택")
    void unregistered_hasSubmitAndApplyDefault() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.UNREGISTERED, null));

      JsonNode root = OM.readTree(json);
      JsonNode view = root.path("view");

      // submit 버튼 존재
      assertThat(view.has("submit")).isTrue();
      assertThat(view.path("submit").path("text").asText()).isNotEmpty();

      // 사용/취소 라디오 블록
      JsonNode blocks = view.path("blocks");
      JsonNode actionBlock = findBlockById(blocks, "block_lunch_card_action");
      assertThat(actionBlock).isNotNull();

      // apply 기본 선택
      JsonNode initialOption = actionBlock.path("element").path("initial_option");
      assertThat(initialOption.path("value").asText()).isEqualTo("apply");
    }

    @Test
    @DisplayName("본인 등록 — submit 버튼 존재 + 취소(cancel) 기본 선택")
    void selfRegistered_hasSubmitAndCancelDefault() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.SELF_REGISTERED, "testuser"));

      JsonNode root = OM.readTree(json);
      JsonNode view = root.path("view");

      // submit 버튼 존재
      assertThat(view.has("submit")).isTrue();

      // 취소 기본 선택
      JsonNode blocks = view.path("blocks");
      JsonNode actionBlock = findBlockById(blocks, "block_lunch_card_action");
      assertThat(actionBlock).isNotNull();

      JsonNode initialOption = actionBlock.path("element").path("initial_option");
      assertThat(initialOption.path("value").asText()).isEqualTo("cancel");
    }

    @Test
    @DisplayName("타인 등록 — submit 버튼 없음 + 안내 텍스트 표시")
    void otherRegistered_noSubmitAndWarning() throws Exception {
      String json = LunchCardModalBuilder.build(
          "T123", dataWithStatus(Status.OTHER_REGISTERED, "김철수"));

      JsonNode root = OM.readTree(json);
      JsonNode view = root.path("view");

      // submit 버튼 없음
      assertThat(view.has("submit")).isFalse();

      // 안내 텍스트 존재
      String blocksJson = view.path("blocks").toString();
      assertThat(blocksJson).contains("김철수");

      // 사용/취소 라디오 블록 없음
      JsonNode actionBlock = findBlockById(view.path("blocks"), "block_lunch_card_action");
      assertThat(actionBlock).isNull();
    }
  }

  @Nested
  @DisplayName("buildUpdate (views.update)")
  class BuildUpdate {

    @Test
    @DisplayName("view_id 사용 + trigger_id 없음")
    void buildUpdate_usesViewId() throws Exception {
      String json = LunchCardModalBuilder.buildUpdate(
          "V123", dataWithStatus(Status.UNREGISTERED, null));

      JsonNode root = OM.readTree(json);
      assertThat(root.path("view_id").asText()).isEqualTo("V123");
      assertThat(root.has("trigger_id")).isFalse();
    }

    @Test
    @DisplayName("view 구조는 build와 동일")
    void buildUpdate_sameViewStructure() throws Exception {
      String json = LunchCardModalBuilder.buildUpdate(
          "V123", dataWithStatus(Status.SELF_REGISTERED, "testuser"));

      JsonNode view = OM.readTree(json).path("view");
      assertThat(view.path("type").asText()).isEqualTo("modal");
      assertThat(view.path("callback_id").asText()).isEqualTo("lunch_card_submit");
    }
  }

  private static JsonNode findBlockById(JsonNode blocks, String blockId) {
    for (JsonNode block : blocks) {
      if (blockId.equals(block.path("block_id").asText())) {
        return block;
      }
    }
    return null;
  }
}
