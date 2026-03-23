package com.riman.automation.common.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Slack Block Kit JSON 빌더
 *
 * <p><b>책임:</b> Block Kit 구조체 JSON 생성만.
 * 어떤 내용을 넣을지는 상위 계층(scheduler service 등)이 결정한다.
 *
 * <p>사용 예:
 * <pre>
 *   String json = SlackBlockBuilder.forChannel("C123")
 *       .fallbackText("일일 보고서")
 *       .header("📊 일일 팀 보고서")
 *       .divider()
 *       .section("*공지*\n• 회식 금요일")
 *       .report();
 * </pre>
 */
public class SlackBlockBuilder {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ObjectNode root;
    private final ArrayNode blocks;

    private SlackBlockBuilder(String channelId) {
        root = OM.createObjectNode();
        blocks = OM.createArrayNode();
        if (channelId != null) root.put("channel", channelId);
    }

    public static SlackBlockBuilder forChannel(String channelId) {
        return new SlackBlockBuilder(channelId);
    }

    /**
     * trigger_id 포함 Modal payload 빌더용
     */
    public static SlackBlockBuilder forModal() {
        return new SlackBlockBuilder(null);
    }

    // ─── 설정 ──────────────────────────────────────────────────────────────────

    public SlackBlockBuilder fallbackText(String text) {
        root.put("text", text);
        return this;
    }

    public SlackBlockBuilder noUnfurl() {
        root.put("unfurl_links", false);
        root.put("unfurl_media", false);
        return this;
    }

    // ─── 블록 타입 ─────────────────────────────────────────────────────────────

    /**
     * 헤더 블록 (plain_text, 볼드 스타일)
     */
    public SlackBlockBuilder header(String text) {
        ObjectNode b = OM.createObjectNode().put("type", "header");
        b.set("text", plainText(text));
        blocks.add(b);
        return this;
    }

    /**
     * mrkdwn 섹션 블록
     */
    public SlackBlockBuilder section(String markdown) {
        ObjectNode b = OM.createObjectNode().put("type", "section");
        b.set("text", mrkdwn(markdown));
        blocks.add(b);
        return this;
    }

    /**
     * 구분선
     */
    public SlackBlockBuilder divider() {
        blocks.add(OM.createObjectNode().put("type", "divider"));
        return this;
    }

    /**
     * Context 블록 (소형 보조 텍스트)
     */
    public SlackBlockBuilder context(String markdown) {
        ObjectNode b = OM.createObjectNode().put("type", "context");
        ArrayNode els = OM.createArrayNode();
        els.add(mrkdwn(markdown));
        b.set("elements", els);
        blocks.add(b);
        return this;
    }

    /**
     * 이미 구성된 custom block JSON Node를 직접 추가
     */
    public SlackBlockBuilder rawBlock(ObjectNode block) {
        blocks.add(block);
        return this;
    }

    /**
     * rich_text 블록 추가 — 텍스트/링크에 색상(hex) 지정 가능
     *
     * <p>Slack mrkdwn section 은 색상을 미지원하므로,
     * 부재 당일 행 빨간색·티켓 긴급도 색상처럼 실제 글자색이 필요한 경우 이 블록을 사용한다.
     *
     * <p>elements 배열에는 rich_text_section / rich_text_list 등을 담는다.
     *
     * @param elements rich_text 하위 elements 배열 (rich_text_section 등)
     */
    public SlackBlockBuilder richText(ArrayNode elements) {
        ObjectNode b = OM.createObjectNode().put("type", "rich_text");
        b.set("elements", elements);
        blocks.add(b);
        return this;
    }

    /**
     * rich_text 블록 구성 시 필요한 ObjectMapper 노출
     */
    public ObjectMapper objectMapper() {
        return OM;
    }

    // ─── 조회 ──────────────────────────────────────────────────────────────────

    /**
     * 현재까지 추가된 블록 수 반환 (Slack 50개 한도 모니터링용)
     */
    public int blockCount() {
        return blocks.size();
    }

    // ─── 빌드 ──────────────────────────────────────────────────────────────────

    public String build() {
        root.set("blocks", blocks);
        try {
            return OM.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Slack Block Kit JSON 직렬화 실패", e);
        }
    }

    // ─── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private ObjectNode plainText(String text) {
        return OM.createObjectNode().put("type", "plain_text").put("text", text).put("emoji", true);
    }

    private ObjectNode mrkdwn(String text) {
        return OM.createObjectNode().put("type", "mrkdwn").put("text", text);
    }
}
