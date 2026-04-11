package com.riman.automation.common.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackBlockBuilderTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("forChannel — channel 필드가 설정된다")
    void forChannel_setsChannelField() throws Exception {
        String json = SlackBlockBuilder.forChannel("C123").build();
        JsonNode root = OM.readTree(json);

        assertThat(root.get("channel").asText()).isEqualTo("C123");
    }

    @Test
    @DisplayName("forModal — channel 필드가 없다")
    void forModal_noChannelField() throws Exception {
        String json = SlackBlockBuilder.forModal().build();
        JsonNode root = OM.readTree(json);

        assertThat(root.has("channel")).isFalse();
    }

    @Test
    @DisplayName("fallbackText — text 필드 설정")
    void fallbackText_setsTextField() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1")
                .fallbackText("알림 텍스트")
                .build();
        JsonNode root = OM.readTree(json);

        assertThat(root.get("text").asText()).isEqualTo("알림 텍스트");
    }

    @Test
    @DisplayName("noUnfurl — unfurl_links/unfurl_media false 설정")
    void noUnfurl_setsBothFalse() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").noUnfurl().build();
        JsonNode root = OM.readTree(json);

        assertThat(root.get("unfurl_links").asBoolean()).isFalse();
        assertThat(root.get("unfurl_media").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("header — type=header, plain_text 구조")
    void header_createsHeaderBlock() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").header("제목").build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("header");
        assertThat(block.get("text").get("type").asText()).isEqualTo("plain_text");
        assertThat(block.get("text").get("text").asText()).isEqualTo("제목");
    }

    @Test
    @DisplayName("section — type=section, mrkdwn 구조")
    void section_createsSectionBlock() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").section("*볼드*").build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("section");
        assertThat(block.get("text").get("type").asText()).isEqualTo("mrkdwn");
        assertThat(block.get("text").get("text").asText()).isEqualTo("*볼드*");
    }

    @Test
    @DisplayName("divider — type=divider")
    void divider_createsDividerBlock() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").divider().build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("divider");
    }

    @Test
    @DisplayName("context — type=context, elements 배열에 mrkdwn 포함")
    void context_createsContextBlock() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").context("부가 정보").build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("context");
        JsonNode element = block.get("elements").get(0);
        assertThat(element.get("type").asText()).isEqualTo("mrkdwn");
        assertThat(element.get("text").asText()).isEqualTo("부가 정보");
    }

    @Test
    @DisplayName("rawBlock — 커스텀 JSON 노드 직접 추가")
    void rawBlock_addsCustomNode() throws Exception {
        ObjectNode custom = OM.createObjectNode().put("type", "image").put("image_url", "https://example.com/img.png");
        String json = SlackBlockBuilder.forChannel("C1").rawBlock(custom).build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("image");
    }

    @Test
    @DisplayName("richText — type=rich_text, elements 배열 포함")
    void richText_createsRichTextBlock() throws Exception {
        SlackBlockBuilder builder = SlackBlockBuilder.forChannel("C1");
        ArrayNode elements = builder.objectMapper().createArrayNode();
        ObjectNode section = builder.objectMapper().createObjectNode().put("type", "rich_text_section");
        elements.add(section);

        String json = builder.richText(elements).build();
        JsonNode block = OM.readTree(json).get("blocks").get(0);

        assertThat(block.get("type").asText()).isEqualTo("rich_text");
        assertThat(block.get("elements").get(0).get("type").asText()).isEqualTo("rich_text_section");
    }

    @Test
    @DisplayName("blockCount — 추가된 블록 수 누적 반환")
    void blockCount_incrementsCorrectly() {
        SlackBlockBuilder builder = SlackBlockBuilder.forChannel("C1");
        assertThat(builder.blockCount()).isZero();

        builder.header("H").section("S").divider();
        assertThat(builder.blockCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("chaining — 여러 블록을 연속으로 추가하고 build 성공")
    void chaining_multipleBlocks_buildSucceeds() throws Exception {
        String json = SlackBlockBuilder.forChannel("C123")
                .fallbackText("보고서")
                .noUnfurl()
                .header("📊 일일 보고서")
                .divider()
                .section("*섹션 1*")
                .context("부가 정보")
                .build();

        JsonNode root = OM.readTree(json);
        assertThat(root.get("blocks").size()).isEqualTo(4);
        assertThat(root.get("channel").asText()).isEqualTo("C123");
        assertThat(root.get("text").asText()).isEqualTo("보고서");
    }

    @Test
    @DisplayName("build — 유효한 JSON 문자열 반환")
    void build_returnsValidJson() throws Exception {
        String json = SlackBlockBuilder.forChannel("C1").header("H").build();

        // JSON 파싱 성공 = 유효한 JSON
        JsonNode root = OM.readTree(json);
        assertThat(root).isNotNull();
        assertThat(root.has("blocks")).isTrue();
    }
}
