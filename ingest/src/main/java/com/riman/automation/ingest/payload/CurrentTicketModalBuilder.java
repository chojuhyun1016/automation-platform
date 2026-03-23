package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.slack.SlackBlockBuilder;

/**
 * /현재티켓 Slack Modal Block Kit JSON 빌더
 *
 * <p><b>모달 구성:</b>
 * <pre>
 *   period 드롭다운 (static_select)
 *     - 일별  (value: daily)
 *     - 주별  (value: weekly)
 *     - 분기별 (value: quarterly)
 * </pre>
 *
 * <p><b>block_id / action_id:</b>
 * <pre>
 *   block_ticket_period / action_ticket_period → 조회 기간 선택
 * </pre>
 *
 * <p><b>private_metadata:</b> "userId" (1단 — 현재티켓은 요청자 본인만 조회)
 *
 * <p><b>callback_id:</b> {@code current_ticket_submit}
 */
public class CurrentTicketModalBuilder {

    public static final String CALLBACK_ID = "current_ticket_submit";

    private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();

    // 드롭다운 옵션 (value, label)
    private static final String[][] PERIOD_OPTIONS = {
            {"daily", "일별"},
            {"weekly", "주별"},
            {"quarterly", "분기별"},
    };

    private CurrentTicketModalBuilder() {
    }

    // =========================================================================
    // 진입점
    // =========================================================================

    /**
     * /현재티켓 모달 JSON 생성
     *
     * @param triggerId Slack trigger_id
     * @param userId    요청자 Slack User ID (private_metadata 저장용)
     */
    public static String build(String triggerId, String userId) throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("trigger_id", triggerId);
        root.set("view", buildView(userId));
        return OM.writeValueAsString(root);
    }

    // =========================================================================
    // view 구성
    // =========================================================================

    private static ObjectNode buildView(String userId) {
        ObjectNode view = OM.createObjectNode();
        view.put("type", "modal");
        view.put("callback_id", CALLBACK_ID);
        view.put("private_metadata", userId);   // userId만 저장 (1단)
        view.put("title", plainText("📋 현재 티켓 조회"));
        view.put("submit", plainText("조회"));
        view.put("close", plainText("취소"));

        ArrayNode blocks = OM.createArrayNode();

        // ── 안내 텍스트 ──────────────────────────────────────────────────────
        ObjectNode infoBlock = OM.createObjectNode();
        infoBlock.put("type", "section");
        ObjectNode infoText = OM.createObjectNode();
        infoText.put("type", "mrkdwn");
        infoText.put("text", "현재 분기 내 *본인 담당 미완료 티켓*을 조회합니다.\n조회 기간을 선택해 주세요.");
        infoBlock.set("text", infoText);
        blocks.add(infoBlock);

        // ── 기간 선택 드롭다운 ───────────────────────────────────────────────
        ObjectNode periodBlock = OM.createObjectNode();
        periodBlock.put("type", "input");
        periodBlock.put("block_id", "block_ticket_period");

        ObjectNode label = OM.createObjectNode();
        label.put("type", "plain_text");
        label.put("text", "조회 기간");
        label.put("emoji", true);
        periodBlock.set("label", label);

        ObjectNode select = OM.createObjectNode();
        select.put("type", "static_select");
        select.put("action_id", "action_ticket_period");

        ObjectNode placeholder = OM.createObjectNode();
        placeholder.put("type", "plain_text");
        placeholder.put("text", "기간을 선택해 주세요");
        placeholder.put("emoji", true);
        select.set("placeholder", placeholder);

        // 기본값: 분기별 선택
        ObjectNode initialOption = OM.createObjectNode();
        ObjectNode initialText = OM.createObjectNode();
        initialText.put("type", "plain_text");
        initialText.put("text", "분기별");
        initialText.put("emoji", true);
        initialOption.set("text", initialText);
        initialOption.put("value", "quarterly");
        select.set("initial_option", initialOption);

        ArrayNode options = OM.createArrayNode();
        for (String[] opt : PERIOD_OPTIONS) {
            ObjectNode option = OM.createObjectNode();
            ObjectNode optText = OM.createObjectNode();
            optText.put("type", "plain_text");
            optText.put("text", opt[1]);
            optText.put("emoji", true);
            option.set("text", optText);
            option.put("value", opt[0]);
            options.add(option);
        }
        select.set("options", options);
        periodBlock.set("element", select);
        blocks.add(periodBlock);

        view.set("blocks", blocks);
        return view;
    }

    // =========================================================================
    // 내부 헬퍼
    // =========================================================================

    private static ObjectNode plainText(String text) {
        ObjectNode node = OM.createObjectNode();
        node.put("type", "plain_text");
        node.put("text", text);
        node.put("emoji", true);
        return node;
    }
}
