package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;

public class RemoteWorkModalBuilder {

    private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();

    private RemoteWorkModalBuilder() {
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
        view.put("callback_id", "remote_work_submit");
        view.set("title", plainText("재택근무"));
        view.set("submit", plainText("확인"));
        view.set("close", plainText("닫기"));
        view.put("private_metadata", userId + "|" + displayName);
        view.set("blocks", buildBlocks(displayName, today));
        return view;
    }

    private static ArrayNode buildBlocks(String displayName, String today) {
        ArrayNode blocks = OM.createArrayNode();

        ObjectNode section = OM.createObjectNode().put("type", "section");
        section.set("text", mrkdwn("*" + displayName + "* 님의 재택근무를 신청하거나 취소합니다."));
        blocks.add(section);

        blocks.add(OM.createObjectNode().put("type", "divider"));

        ObjectNode datePicker = OM.createObjectNode()
                .put("type", "datepicker")
                .put("action_id", "action_date")
                .put("initial_date", today);
        datePicker.set("placeholder", plainText("날짜를 선택하세요"));
        blocks.add(inputBlock("block_date", "날짜 선택", datePicker, false));

        ObjectNode applyOpt = option(plainText("신청"), "apply");
        ObjectNode cancelOpt = option(plainText("취소"), "cancel");

        ObjectNode radioGroup = OM.createObjectNode()
                .put("type", "radio_buttons")
                .put("action_id", "action_type");
        radioGroup.set("options", OM.createArrayNode().add(applyOpt).add(cancelOpt));
        radioGroup.set("initial_option", applyOpt);
        blocks.add(inputBlock("block_action_type", "구분", radioGroup, false));

        return blocks;
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

    private static ObjectNode option(ObjectNode textNode, String value) {
        ObjectNode node = OM.createObjectNode();
        node.set("text", textNode);
        node.put("value", value);
        return node;
    }

    private static ObjectNode plainText(String text) {
        return OM.createObjectNode().put("type", "plain_text").put("text", text).put("emoji", true);
    }

    private static ObjectNode mrkdwn(String text) {
        return OM.createObjectNode().put("type", "mrkdwn").put("text", text);
    }
}
