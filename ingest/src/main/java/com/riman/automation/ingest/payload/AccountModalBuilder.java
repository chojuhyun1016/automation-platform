package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.model.GroupwareAccountInfo;
import com.riman.automation.common.slack.SlackBlockBuilder;

/**
 * /계정관리 Slack Modal Block Kit JSON 빌더.
 * 계정 존재 여부에 따라 변경/삭제 모달과 등록 모달 두 가지를 생성한다.
 * callback_id 는 {@code account_manage_submit} 이며 ObjectMapper 는 SlackBlockBuilder 와 공유한다.
 * Slack 은 password 타입 input 을 지원하지 않으므로 plain_text_input + placeholder 힌트로 대응한다.
 */
public class AccountModalBuilder {

  public static final String CALLBACK_ID = "account_manage_submit";

  private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();

  private AccountModalBuilder() {
  }

  /**
   * 계정이 이미 등록된 경우의 변경/삭제 모달 JSON 을 생성한다.
   *
   * @param triggerId   Slack trigger_id
   * @param displayName Modal 에 표시할 이름
   * @param userId      Slack User ID (private_metadata 저장용)
   * @param existing    기존 계정 정보 (ID initial_value 설정용)
   */
  public static String buildUpdateModal(
      String triggerId, String displayName, String userId,
      GroupwareAccountInfo existing) throws Exception {
    ObjectNode root = OM.createObjectNode();
    root.put("trigger_id", triggerId);
    root.set("view", buildView(displayName, userId, existing));
    return OM.writeValueAsString(root);
  }

  /**
   * 계정이 미등록된 경우의 등록 모달 JSON 을 생성한다.
   */
  public static String buildRegisterModal(
      String triggerId, String displayName, String userId) throws Exception {
    ObjectNode root = OM.createObjectNode();
    root.put("trigger_id", triggerId);
    root.set("view", buildView(displayName, userId, null));
    return OM.writeValueAsString(root);
  }

  private static ObjectNode buildView(
      String displayName, String userId, GroupwareAccountInfo existing) {
    boolean hasAccount = (existing != null && existing.hasId());

    ObjectNode view = OM.createObjectNode();
    view.put("type", "modal");
    view.put("callback_id", CALLBACK_ID);
    view.set("title", plainText("계정관리"));
    view.set("submit", plainText(hasAccount ? "변경" : "등록"));
    view.set("close", plainText("닫기"));
    // private_metadata 형식: "userId|displayName|action" (AccountModalSubmit 에서 복원).
    view.put("private_metadata",
        userId + "|" + displayName + "|" + (hasAccount ? "update" : "register"));
    view.set("blocks", buildBlocks(displayName, existing, hasAccount));
    return view;
  }

  private static ArrayNode buildBlocks(
      String displayName, GroupwareAccountInfo existing, boolean hasAccount) {
    ArrayNode blocks = OM.createArrayNode();

    // 계정 존재 여부에 따라 안내 문구를 분기한다.
    String guide = hasAccount
        ? "*" + displayName + "* 님의 그룹웨어 계정 정보입니다.\n비밀번호를 변경하거나 계정을 삭제할 수 있습니다."
        : "*" + displayName + "* 님의 그룹웨어 계정이 등록되어 있지 않습니다.\n그룹웨어 ID(사번)와 비밀번호를 등록해 주세요.";
    blocks.add(section(guide));
    blocks.add(divider());

    blocks.add(buildGroupwareIdBlock(existing));
    blocks.add(buildPasswordBlock(existing));

    if (hasAccount) {
      blocks.add(context(
          "⚠️ *Slack은 비밀번호 마스킹을 지원하지 않습니다.* 주변에 화면이 노출되지 않는 환경에서 입력하세요.\n"
              + "🔒 입력한 비밀번호는 *KMS 암호화* 후 Secrets Manager에 안전하게 저장됩니다.\n"
              + "💡 새 비밀번호 입력 후 *변경* 버튼을 누르세요.\n"
              + "💡 계정 삭제는 아래 삭제 버튼을 클릭하세요. ID와 비밀번호 일치 확인 후 삭제됩니다."));
      blocks.add(buildDeleteButtonBlock());
    } else {
      blocks.add(context(
          "⚠️ *Slack은 비밀번호 마스킹을 지원하지 않습니다.* 주변에 화면이 노출되지 않는 환경에서 입력하세요.\n"
              + "🔒 입력한 비밀번호는 *KMS 암호화* 후 Secrets Manager에 안전하게 저장됩니다."));
    }

    return blocks;
  }

  private static ObjectNode buildGroupwareIdBlock(GroupwareAccountInfo existing) {
    ObjectNode input = OM.createObjectNode();
    input.put("type", "plain_text_input");
    input.put("action_id", "action_groupware_id");
    input.put("max_length", 50);

    if (existing != null && existing.hasId()) {
      // 기존 ID 를 initial_value 로 자동 입력한다.
      input.put("initial_value", existing.getGroupwareId());
      input.set("placeholder", plainText("그룹웨어 ID(사번)"));
    } else {
      input.set("placeholder", plainText("그룹웨어 ID(사번)를 입력하세요"));
    }

    return inputBlock("block_groupware_id", "그룹웨어 ID (사번)", input, false);
  }

  /**
   * 비밀번호 입력 블록.
   * Slack 이 password 타입 input(마스킹)을 미지원하므로 plain_text_input 을 사용하되
   * initial_value 를 절대 설정하지 않아 기존 비밀번호 노출을 방지한다.
   * 최종 저장은 KMS 암호화 후 Secrets Manager 에 보관된다.
   */
  private static ObjectNode buildPasswordBlock(GroupwareAccountInfo existing) {
    ObjectNode input = OM.createObjectNode();
    input.put("type", "plain_text_input");
    input.put("action_id", "action_groupware_password");
    input.put("max_length", 100);

    String placeholder = (existing != null)
        ? "새 비밀번호 입력 (변경 시에만 입력, 미입력 시 변경 안 됨)"
        : "비밀번호를 입력하세요";
    input.set("placeholder", plainText(placeholder));

    return inputBlock("block_groupware_password", "🔒 비밀번호", input, false);
  }

  /**
   * 계정 삭제 버튼 블록 (danger style + confirm 다이얼로그).
   * 사용자 확인 승인 후 block_actions(action_account_delete)이 수신되면
   * payload 의 ID/PW 를 검증한 뒤 SlackClient.updateView() 로 결과 화면을 교체한다.
   */
  private static ObjectNode buildDeleteButtonBlock() {
    ObjectNode confirm = OM.createObjectNode();
    confirm.set("title", plainText("계정 삭제 확인"));
    confirm.set("text", mrkdwn("정말 삭제하시겠습니까?\n위에 입력한 ID와 비밀번호로 삭제됩니다."));
    confirm.set("confirm", plainText("삭제"));
    confirm.set("deny", plainText("취소"));
    confirm.put("style", "danger");

    ObjectNode button = OM.createObjectNode();
    button.put("type", "button");
    button.put("action_id", "action_account_delete");
    button.set("text", plainText("🗑️ 계정 삭제"));
    button.put("style", "danger");
    button.put("value", "delete");
    button.set("confirm", confirm);

    ArrayNode elements = OM.createArrayNode();
    elements.add(button);

    ObjectNode block = OM.createObjectNode();
    block.put("type", "actions");
    block.put("block_id", "block_delete_action");
    block.set("elements", elements);
    return block;
  }

  /**
   * 삭제 결과 화면 JSON 을 생성한다 (views.update 전용).
   * submit 버튼 없이 '닫기'만 제공하여 사용자가 결과 확인 후 종료하도록 한다.
   *
   * @param viewId  block_actions payload 의 view.id
   * @param success true=성공, false=실패
   * @param message 표시할 메시지
   */
  public static String buildResultView(
      String viewId, boolean success, String message) throws Exception {
    String icon = success ? "✅" : "❌";

    ObjectNode text = OM.createObjectNode()
        .put("type", "mrkdwn")
        .put("text", icon + "  " + message);

    ObjectNode section = OM.createObjectNode().put("type", "section");
    section.set("text", text);

    ArrayNode blocks = OM.createArrayNode();
    blocks.add(section);

    ObjectNode view = OM.createObjectNode().put("type", "modal");
    view.set("title", plainText("계정관리"));
    view.set("close", plainText("닫기"));
    view.set("blocks", blocks);

    ObjectNode root = OM.createObjectNode();
    root.put("view_id", viewId);
    root.set("view", view);
    return OM.writeValueAsString(root);
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

  private static ObjectNode section(String markdown) {
    ObjectNode b = OM.createObjectNode().put("type", "section");
    b.set("text", mrkdwn(markdown));
    return b;
  }

  private static ObjectNode divider() {
    return OM.createObjectNode().put("type", "divider");
  }

  private static ObjectNode context(String markdown) {
    ObjectNode b = OM.createObjectNode().put("type", "context");
    ArrayNode els = OM.createArrayNode();
    els.add(mrkdwn(markdown));
    b.set("elements", els);
    return b;
  }

  private static ObjectNode plainText(String text) {
    return OM.createObjectNode()
        .put("type", "plain_text")
        .put("text", text)
        .put("emoji", true);
  }

  private static ObjectNode mrkdwn(String text) {
    return OM.createObjectNode()
        .put("type", "mrkdwn")
        .put("text", text);
  }
}
