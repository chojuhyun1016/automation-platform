package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.slack.SlackBlockBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /점심카드 Slack Modal Block Kit JSON 빌더.
 * callback_id 는 {@code lunch_card_submit} 이다.
 *
 * 블록 구성:
 * 1. 인사 섹션
 * 2. Datepicker (dispatch_action — 날짜 변경 시 views.update)
 * 3. 카운트 표시 (주간 + 월간 동시 표시)
 * 4. 요일별 사용자 목록 (월~금)
 * 5. 상태별 액션 영역:
 *    - UNREGISTERED: 사용/취소 라디오 (사용 기본) + submit 버튼
 *    - SELF_REGISTERED: 사용/취소 라디오 (취소 기본) + submit 버튼
 *    - OTHER_REGISTERED: 안내 텍스트 + submit 버튼 없음
 */
public class LunchCardModalBuilder {

  private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();
  private static final String CALLBACK_ID = "lunch_card_submit";

  private LunchCardModalBuilder() {
  }

  /** 점심카드 사용 상태. */
  public enum Status {
    UNREGISTERED,       // 미등록 — 사용 활성
    SELF_REGISTERED,    // 본인 등록 — 취소 자동선택
    OTHER_REGISTERED    // 타인 등록 — 비활성 + 안내
  }

  /** 모달 렌더링에 필요한 데이터. */
  public record ViewData(
      String userName,
      String userId,
      String selectedDate,
      Status status,
      String registeredUserName,
      int weeklyCount,
      int monthlyCount,
      Map<String, List<String>> dayOfWeekMap
  ) {}

  /**
   * views.open 용 모달 JSON (trigger_id + view).
   */
  public static String build(String triggerId, ViewData data) throws Exception {
    ObjectNode root = OM.createObjectNode();
    root.put("trigger_id", triggerId);
    root.set("view", buildView(data));
    return OM.writeValueAsString(root);
  }

  /**
   * views.update 용 모달 JSON (view_id + view).
   */
  public static String buildUpdate(String viewId, ViewData data) throws Exception {
    ObjectNode root = OM.createObjectNode();
    root.put("view_id", viewId);
    root.set("view", buildView(data));
    return OM.writeValueAsString(root);
  }

  private static ObjectNode buildView(ViewData data) {
    ObjectNode view = OM.createObjectNode();
    view.put("type", "modal");
    view.put("callback_id", CALLBACK_ID);
    view.set("title", plainText("점심카드"));
    view.set("close", plainText("닫기"));
    view.put("private_metadata", data.userId() + "|" + data.userName());

    if (data.status() != Status.OTHER_REGISTERED) {
      view.set("submit", plainText("신청"));
    }

    view.set("blocks", buildBlocks(data));
    return view;
  }

  private static ArrayNode buildBlocks(ViewData data) {
    ArrayNode blocks = OM.createArrayNode();

    // 1. 인사 섹션
    ObjectNode greeting = OM.createObjectNode().put("type", "section");
    greeting.set("text", mrkdwn("*" + data.userName() + "* 님의 점심카드"));
    blocks.add(greeting);

    blocks.add(divider());

    // 2. Datepicker (dispatch_action)
    ObjectNode datePicker = OM.createObjectNode()
        .put("type", "datepicker")
        .put("action_id", "action_lunch_card_date")
        .put("initial_date", data.selectedDate());
    datePicker.set("placeholder", plainText("날짜를 선택하세요"));
    ObjectNode dateBlock = inputBlock(
        "block_lunch_card_date", "날짜 선택", datePicker, false);
    dateBlock.put("dispatch_action", true);
    blocks.add(dateBlock);

    // 3. 카운트 표시 (주간 + 월간 동시)
    ObjectNode countSection = OM.createObjectNode().put("type", "section");
    ArrayNode fields = OM.createArrayNode();
    fields.add(mrkdwn("📊 주간 사용: *" + data.weeklyCount() + "*회"));
    fields.add(mrkdwn("📊 월간 사용: *" + data.monthlyCount() + "*회"));
    countSection.set("fields", fields);
    blocks.add(countSection);

    blocks.add(divider());

    // 4. 요일별 사용자 목록
    ObjectNode weekHeader = OM.createObjectNode().put("type", "section");
    weekHeader.set("text", mrkdwn("📋 *이번 주 사용자 현황*"));
    blocks.add(weekHeader);

    if (data.dayOfWeekMap() != null) {
      String selectedDayLabel = selectedDayLabel(data.selectedDate());
      for (Map.Entry<String, List<String>> entry : data.dayOfWeekMap().entrySet()) {
        String day = entry.getKey();
        List<String> users = entry.getValue();
        boolean highlight = day.equals(selectedDayLabel);
        String userText;
        if (users.isEmpty()) {
          userText = "_없음_";
        } else if (highlight) {
          userText = users.stream().map(n -> "`" + n + "`").collect(java.util.stream.Collectors.joining(", "));
        } else {
          userText = String.join(", ", users);
        }
        ObjectNode daySection = OM.createObjectNode().put("type", "section");
        daySection.set("text", mrkdwn("*" + day + "* — " + userText));
        blocks.add(daySection);
      }
    }

    blocks.add(divider());

    // 5. 상태별 액션 영역
    switch (data.status()) {
      case UNREGISTERED -> addActionBlock(blocks, "apply");
      case SELF_REGISTERED -> addActionBlock(blocks, "cancel");
      case OTHER_REGISTERED -> addOtherRegisteredNotice(blocks, data.registeredUserName());
    }

    return blocks;
  }

  private static void addActionBlock(ArrayNode blocks, String initialValue) {
    ObjectNode applyOpt = option(plainText("사용"), "apply");
    ObjectNode cancelOpt = option(plainText("취소"), "cancel");

    ObjectNode radioGroup = OM.createObjectNode()
        .put("type", "radio_buttons")
        .put("action_id", "action_lunch_card_action");
    radioGroup.set("options", OM.createArrayNode().add(applyOpt).add(cancelOpt));
    radioGroup.set("initial_option", "cancel".equals(initialValue) ? cancelOpt : applyOpt);
    blocks.add(inputBlock("block_lunch_card_action", "신청/취소", radioGroup, false));
  }

  private static void addOtherRegisteredNotice(ArrayNode blocks, String registeredUserName) {
    String name = (registeredUserName != null) ? registeredUserName : "다른 사용자";
    ObjectNode notice = OM.createObjectNode().put("type", "section");
    notice.set("text", mrkdwn("⚠️ *" + name + "* 님이 이미 사용 중입니다.\n"
        + "취소가 필요하면 해당 사용자에게 요청해 주세요."));
    blocks.add(notice);
  }

  private static ObjectNode inputBlock(
      String blockId, String label, ObjectNode element, boolean optional) {
    ObjectNode block = OM.createObjectNode();
    block.put("type", "input");
    block.put("block_id", blockId);
    block.set("label", plainText(label));
    block.set("element", element);
    if (optional) block.put("optional", true);
    return block;
  }

  private static ObjectNode divider() {
    return OM.createObjectNode().put("type", "divider");
  }

  private static ObjectNode option(ObjectNode textNode, String value) {
    ObjectNode node = OM.createObjectNode();
    node.set("text", textNode);
    node.put("value", value);
    return node;
  }

  /** selectedDate(yyyy-MM-dd)의 요일을 "월"~"일" 한글 라벨로 변환. */
  static String selectedDayLabel(String selectedDate) {
    if (selectedDate == null) return "";
    DayOfWeek dow = LocalDate.parse(selectedDate).getDayOfWeek();
    return switch (dow) {
      case MONDAY -> "월";
      case TUESDAY -> "화";
      case WEDNESDAY -> "수";
      case THURSDAY -> "목";
      case FRIDAY -> "금";
      case SATURDAY -> "토";
      case SUNDAY -> "일";
    };
  }

  private static ObjectNode plainText(String text) {
    return OM.createObjectNode().put("type", "plain_text").put("text", text).put("emoji", true);
  }

  private static ObjectNode mrkdwn(String text) {
    return OM.createObjectNode().put("type", "mrkdwn").put("text", text);
  }
}
