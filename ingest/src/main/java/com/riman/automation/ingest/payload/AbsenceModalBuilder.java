package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.code.AbsenceTypeCode;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;

public class AbsenceModalBuilder {

    private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();

    private AbsenceModalBuilder() {
    }

    public static String build(String triggerId, String displayName, String userId) throws Exception {
        String today = DateTimeUtil.formatDate(DateTimeUtil.todayKst());

        ObjectNode root = OM.createObjectNode();
        root.put("trigger_id", triggerId);
        root.set("view", buildView(displayName, userId, today));
        return OM.writeValueAsString(root);
    }

    private static ObjectNode buildView(String displayName, String userId, String today) {
        ObjectNode view = OM.createObjectNode();
        view.put("type", "modal");
        view.put("callback_id", "absence_submit");
        view.set("title", plainText("부재등록"));
        view.set("submit", plainText("확인"));
        view.set("close", plainText("닫기"));
        view.put("private_metadata", userId + "|" + displayName);
        view.set("blocks", buildBlocks(displayName, today));
        return view;
    }

    private static ArrayNode buildBlocks(String displayName, String today) {
        ArrayNode blocks = OM.createArrayNode();

        blocks.add(section("*" + displayName + "* 님의 부재를 등록하거나 취소합니다."));
        blocks.add(divider());
        blocks.add(buildAbsenceTypeBlock());
        blocks.add(buildActionTypeBlock());
        blocks.add(buildDatePickerBlock("block_start_date", "action_start_date", "시작일", today));
        blocks.add(buildDatePickerBlock("block_end_date", "action_end_date", "종료일", today));
        blocks.add(context(
                "💡 *오전/오후 반차, 반반차, 보건 휴가, 예비군(민방위) 훈련*은 시작일만 사용됩니다.\n" +
                        "💡 사유를 입력하지 않으면 *개인사유*로 저장됩니다.\n" +
                        "💡 취소 시 해당 날짜·유형의 본인 이벤트가 삭제됩니다."
        ));
        blocks.add(buildReasonBlock());

        return blocks;
    }

    private static ObjectNode buildAbsenceTypeBlock() {
        ArrayNode options = OM.createArrayNode();
        for (AbsenceTypeCode t : AbsenceTypeCode.values()) {
            options.add(option(t.getLabel(), t.getLabel()));
        }

        ObjectNode select = OM.createObjectNode();
        select.put("type", "static_select");
        select.put("action_id", "action_absence_type");
        select.set("placeholder", plainText("부재 유형을 선택하세요"));
        select.set("options", options);
        select.set("initial_option",
                option(
                        AbsenceTypeCode.ANNUAL_LEAVE.getLabel(),
                        AbsenceTypeCode.ANNUAL_LEAVE.getLabel()
                ));

        return inputBlock("block_absence_type", "부재 유형", select, false);
    }

    private static ObjectNode buildActionTypeBlock() {
        ObjectNode applyOpt = option("등록", "apply");
        ObjectNode cancelOpt = option("취소", "cancel");

        ObjectNode radio = OM.createObjectNode();
        radio.put("type", "radio_buttons");
        radio.put("action_id", "action_action_type");
        radio.set("options", OM.createArrayNode().add(applyOpt).add(cancelOpt));
        radio.set("initial_option", applyOpt);

        return inputBlock("block_action_type", "구분", radio, false);
    }

    private static ObjectNode buildDatePickerBlock(
            String blockId, String actionId, String label, String initialDate) {
        ObjectNode dp = OM.createObjectNode();
        dp.put("type", "datepicker");
        dp.put("action_id", actionId);
        dp.put("initial_date", initialDate);
        dp.set("placeholder", plainText("날짜를 선택하세요"));

        return inputBlock(blockId, label, dp, false);
    }

    private static ObjectNode buildReasonBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "plain_text_input");
        input.put("action_id", "action_reason");
        input.put("multiline", false);
        input.set("placeholder", plainText("사유 (미입력 시 개인사유로 저장)"));

        return inputBlock("block_reason", "사유", input, true);
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

    private static ObjectNode option(String label, String value) {
        ObjectNode node = OM.createObjectNode();
        node.set("text", plainText(label));
        node.put("value", value);
        return node;
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
