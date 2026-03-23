package com.riman.automation.ingest.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.ingest.service.ScheduleMappingQueryService;

import java.util.List;

/**
 * 일정등록/삭제 통합 Slack Modal Block Kit JSON 빌더
 *
 * <p><b>두 가지 모드:</b>
 * <pre>
 * [등록된 일정 없을 때] buildRegisterOnlyModal()
 *   → 등록 폼만 표시
 *
 * [등록된 일정 있을 때] buildRegisterAndDeleteModal()
 *   → 상단: 내 일정 삭제 (드롭다운 + 삭제 버튼)
 *   → 구분선
 *   → 하단: 새 일정 등록 폼
 * </pre>
 *
 * <p><b>등록 폼 필드 (block_id / action_id):</b>
 * <pre>
 *   block_schedule_title        / action_schedule_title        → 제목 (필수)
 *   block_schedule_description  / action_schedule_description  → 내용 (optional)
 *   block_schedule_start_date   / action_schedule_start_date   → 시작 날짜 datepicker (필수)
 *   block_schedule_start_time   / action_schedule_start_time   → 시작 시간 timepicker (optional)
 *   block_schedule_end_time     / action_schedule_end_time     → 종료 시간 timepicker (optional, 미입력 시 +1시간 자동)
 *   block_schedule_reminder_1   / action_schedule_reminder_1   → 알림1 static_select (optional)
 *   block_schedule_reminder_2   / action_schedule_reminder_2   → 알림2 static_select (optional)
 *   block_schedule_reminder_3   / action_schedule_reminder_3   → 알림3 static_select (optional)
 *   block_schedule_url          / action_schedule_url          → URL (optional)
 * </pre>
 *
 * <p><b>private_metadata 구조:</b> "userId|slackDisplayName|한글이름" (3단)
 *
 * <p><b>알림 옵션 value (분 단위):</b>
 * <pre>
 *   0    → 없음
 *   60   → 1시간 전
 *   120  → 2시간 전
 *   180  → 3시간 전
 *   1440 → 1일 전
 *   2880 → 2일 전
 *   4320 → 3일 전
 * </pre>
 */
public class ScheduleModalBuilder {

    public static final String CALLBACK_ID = "schedule_submit";
    public static final String ACTION_DELETE_ID = "action_schedule_delete";

    private static final ObjectMapper OM = SlackBlockBuilder.forModal().objectMapper();

    // 알림 드롭다운 옵션 (value=분수, label=표시 텍스트)
    private static final String[][] REMINDER_OPTIONS = {
            {"0", "없음"},
            {"60", "1시간 전"},
            {"120", "2시간 전"},
            {"180", "3시간 전"},
            {"1440", "1일 전"},
            {"2880", "2일 전"},
            {"4320", "3일 전"},
    };

    private ScheduleModalBuilder() {
    }

    // =========================================================================
    // 진입점
    // =========================================================================

    /**
     * 등록된 일정 없을 때 — 등록 전용 모달
     *
     * @param triggerId   Slack trigger_id
     * @param displayName Slack display name
     * @param userId      Slack User ID (private_metadata 저장용)
     * @param koreanName  한글 이름 (모달 작성자 표시 + private_metadata 저장용)
     */
    public static String buildRegisterOnlyModal(
            String triggerId, String displayName, String userId, String koreanName) throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("trigger_id", triggerId);
        root.set("view", buildView(displayName, userId, koreanName, null));
        return OM.writeValueAsString(root);
    }

    /**
     * 등록된 일정 있을 때 — 등록 + 삭제 통합 모달
     *
     * @param triggerId   Slack trigger_id
     * @param displayName Slack display name
     * @param userId      Slack User ID
     * @param koreanName  한글 이름
     * @param mySchedules 본인 등록 일정 목록 (삭제 드롭다운 구성용)
     */
    public static String buildRegisterAndDeleteModal(
            String triggerId, String displayName, String userId,
            String koreanName,
            List<ScheduleMappingQueryService.MappingEntry> mySchedules) throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("trigger_id", triggerId);
        root.set("view", buildView(displayName, userId, koreanName, mySchedules));
        return OM.writeValueAsString(root);
    }

    // =========================================================================
    // View
    // =========================================================================

    private static ObjectNode buildView(
            String displayName, String userId, String koreanName,
            List<ScheduleMappingQueryService.MappingEntry> mySchedules) {
        ObjectNode view = OM.createObjectNode();
        view.put("type", "modal");
        view.put("callback_id", CALLBACK_ID);
        view.set("title", plainText("📅 일정등록"));
        view.set("submit", plainText("등록"));
        view.set("close", plainText("닫기"));
        // private_metadata: "userId|slackDisplayName|한글이름" (3단 구조)
        String koreanNameSafe = (koreanName != null && !koreanName.isBlank()) ? koreanName : displayName;
        view.put("private_metadata", userId + "|" + displayName + "|" + koreanNameSafe);
        view.set("blocks", buildBlocks(displayName, koreanName, mySchedules));
        return view;
    }

    // =========================================================================
    // Blocks
    // =========================================================================

    private static ArrayNode buildBlocks(
            String displayName, String koreanName,
            List<ScheduleMappingQueryService.MappingEntry> mySchedules) {

        ArrayNode blocks = OM.createArrayNode();

        // ── 삭제 섹션 (등록된 일정 있을 때만 표시) ───────────────────────────
        if (mySchedules != null && !mySchedules.isEmpty()) {
            int count = mySchedules.size();
            String infoText = "🗑 *등록된 일정 삭제*\n"
                    + "등록된 일정이 최대 10개까지 날짜 오름차순으로 표시됩니다.\n"
                    + "삭제할 일정을 선택한 후 삭제 버튼을 클릭하세요.";
            blocks.add(section(infoText));
            blocks.add(buildDeleteDropdown(mySchedules));
            blocks.add(buildDeleteButtonBlock());
            blocks.add(divider());
        }

        // ── 등록 섹션 ─────────────────────────────────────────────────────────
        // 작성자 표시
        String authorText = (koreanName != null && !koreanName.isBlank())
                ? koreanName + " (@" + displayName + ")"
                : displayName;
        blocks.add(section("➕ *새 일정 등록*\n✏️ 작성자: " + authorText));

        // 제목 (필수)
        blocks.add(buildTitleBlock());

        // 내용 (optional)
        blocks.add(buildDescriptionBlock());

        // 날짜 (필수)
        blocks.add(buildStartDateBlock());

        // 시작 시간 (optional)
        blocks.add(buildStartTimeBlock());

        // 종료 시간 (optional) — 미입력 시 시작 시간 +1시간 자동 설정 안내
        blocks.add(buildEndTimeBlock());

        // 알림 드롭다운 3개 (optional)
        blocks.add(buildReminderBlock(1));
        blocks.add(buildReminderBlock(2));
        blocks.add(buildReminderBlock(3));

        // URL (optional)
        blocks.add(buildUrlBlock());

        return blocks;
    }

    // =========================================================================
    // 삭제 섹션 — 드롭다운 + 삭제 버튼
    // =========================================================================

    /**
     * 삭제 대상 선택 드롭다운 (static_select)
     *
     * <p>첫 번째 옵션은 항상 "── 선택 안 함 ──" (value="_none_") 으로,
     * 선택한 일정을 되돌리거나 아무것도 선택하지 않은 상태로 복귀할 수 있다.
     * ScheduleManageFacade.handleBlockAction() 에서 "_none_" 이면 삭제 미진행.
     *
     * <p>옵션 value = calendarEventId (block_actions 에서 직접 읽어 삭제 처리)
     */
    private static ObjectNode buildDeleteDropdown(
            List<ScheduleMappingQueryService.MappingEntry> entries) {

        ArrayNode options = OM.createArrayNode();

        // 첫 항목: "선택 안 함" — 선택 해제/복귀용
        ObjectNode noneOption = OM.createObjectNode();
        noneOption.set("text", plainText("선택하지 않음"));
        noneOption.put("value", "_none_");
        options.add(noneOption);

        // 실제 일정 목록
        for (ScheduleMappingQueryService.MappingEntry entry : entries) {
            ObjectNode option = OM.createObjectNode();
            String label = entry.toDisplayLabel();
            if (label.length() > 75) label = label.substring(0, 72) + "...";
            option.set("text", plainText(label));
            option.put("value", entry.eventId);
            options.add(option);
        }

        // "선택 안 함"을 기본 선택 상태로 표시
        ObjectNode initialOption = OM.createObjectNode();
        initialOption.set("text", plainText("선택하지 않음"));
        initialOption.put("value", "_none_");

        ObjectNode select = OM.createObjectNode();
        select.put("type", "static_select");
        select.put("action_id", "action_schedule_select");
        select.set("placeholder", plainText("삭제할 일정을 선택해 주세요"));
        select.set("options", options);
        select.set("initial_option", initialOption);  // 기본값: 선택 안 함

        ObjectNode block = OM.createObjectNode();
        block.put("type", "actions");
        block.put("block_id", "block_schedule_select");
        ArrayNode elements = OM.createArrayNode();
        elements.add(select);
        block.set("elements", elements);
        return block;
    }

    /**
     * 삭제 버튼 (danger style + confirm 다이얼로그)
     */
    private static ObjectNode buildDeleteButtonBlock() {
        ObjectNode confirm = OM.createObjectNode();
        confirm.set("title", plainText("일정 삭제 확인"));
        confirm.set("text", mrkdwn("선택한 일정을 캘린더에서 삭제하시겠습니까?"));
        confirm.set("confirm", plainText("삭제"));
        confirm.set("deny", plainText("취소"));
        confirm.put("style", "danger");

        ObjectNode button = OM.createObjectNode();
        button.put("type", "button");
        button.put("action_id", ACTION_DELETE_ID);
        button.set("text", plainText("🗑️ 삭제"));
        button.put("style", "danger");
        button.put("value", "delete");
        button.set("confirm", confirm);

        ArrayNode elements = OM.createArrayNode();
        elements.add(button);

        ObjectNode block = OM.createObjectNode();
        block.put("type", "actions");
        block.put("block_id", "block_schedule_delete");
        block.set("elements", elements);
        return block;
    }

    // =========================================================================
    // 등록 섹션 — 입력 필드
    // =========================================================================

    /**
     * 제목 입력 (필수)
     */
    private static ObjectNode buildTitleBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "plain_text_input");
        input.put("action_id", "action_schedule_title");
        input.put("max_length", 100);
        input.set("placeholder", plainText("일정 제목 ([일정] 이 자동으로 붙습니다)"));
        return inputBlock("block_schedule_title", "제목", input, false);
    }

    /**
     * 내용 입력 (optional, 멀티라인)
     */
    private static ObjectNode buildDescriptionBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "plain_text_input");
        input.put("action_id", "action_schedule_description");
        input.put("multiline", true);
        input.put("max_length", 500);
        input.set("placeholder", plainText("내용을 입력하세요 (선택사항)"));
        return inputBlock("block_schedule_description", "내용", input, true);
    }

    /**
     * 시작 날짜 datepicker (필수)
     */
    private static ObjectNode buildStartDateBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "datepicker");
        input.put("action_id", "action_schedule_start_date");
        input.set("placeholder", plainText("날짜를 선택하세요"));
        return inputBlock("block_schedule_start_date", "날짜", input, false);
    }

    /**
     * 시작 시간 timepicker (optional) — 미선택 시 종일 일정
     */
    private static ObjectNode buildStartTimeBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "timepicker");
        input.put("action_id", "action_schedule_start_time");
        input.set("placeholder", plainText("시작 시간 (미선택 시 종일 일정)"));
        return inputBlock("block_schedule_start_time", "시작 시간 (선택사항)", input, true);
    }

    /**
     * 종료 시간 timepicker (optional)
     * hint: 미입력 시 시작 시간 +1시간 자동 설정 안내
     */
    private static ObjectNode buildEndTimeBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "timepicker");
        input.put("action_id", "action_schedule_end_time");
        input.set("placeholder", plainText("종료 시간"));

        ObjectNode block = OM.createObjectNode();
        block.put("type", "input");
        block.put("block_id", "block_schedule_end_time");
        block.set("label", plainText("종료 시간 (선택사항)"));
        block.set("element", input);
        block.put("optional", true);
        // hint: 미입력 시 동작 안내
        ObjectNode hint = OM.createObjectNode();
        hint.put("type", "plain_text");
        hint.put("text", "⏰ 입력하지 않으면 시작 시간으로부터 1시간 후로 자동 설정됩니다.");
        hint.put("emoji", true);
        block.set("hint", hint);
        return block;
    }

    /**
     * 알림 드롭다운 (static_select, optional)
     *
     * @param index 1, 2, 3
     */
    private static ObjectNode buildReminderBlock(int index) {
        ArrayNode options = OM.createArrayNode();
        for (String[] opt : REMINDER_OPTIONS) {
            ObjectNode option = OM.createObjectNode();
            option.set("text", plainText(opt[1]));
            option.put("value", opt[0]);
            options.add(option);
        }

        // 기본값 "없음" (value=0)
        ObjectNode initialOption = OM.createObjectNode();
        initialOption.set("text", plainText("없음"));
        initialOption.put("value", "0");

        ObjectNode select = OM.createObjectNode();
        select.put("type", "static_select");
        select.put("action_id", "action_schedule_reminder_" + index);
        select.set("placeholder", plainText("알림 " + index));
        select.set("options", options);
        select.set("initial_option", initialOption);

        String label = index == 1 ? "알림 (선택사항)" : "알림 " + index + " (선택사항)";
        return inputBlock("block_schedule_reminder_" + index, label, select, true);
    }

    /**
     * URL 입력 (optional)
     */
    private static ObjectNode buildUrlBlock() {
        ObjectNode input = OM.createObjectNode();
        input.put("type", "plain_text_input");
        input.put("action_id", "action_schedule_url");
        input.put("max_length", 500);
        input.set("placeholder", plainText("관련 URL (선택사항)"));
        return inputBlock("block_schedule_url", "URL", input, true);
    }

    // =========================================================================
    // 결과 화면 (views.update 용)
    // =========================================================================

    /**
     * 삭제/등록 결과 화면 JSON 생성 (views.update 전용)
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
        view.set("title", plainText("📅 일정등록"));
        view.set("close", plainText("닫기"));
        view.set("blocks", blocks);

        ObjectNode root = OM.createObjectNode();
        root.put("view_id", viewId);
        root.set("view", view);
        return OM.writeValueAsString(root);
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

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
