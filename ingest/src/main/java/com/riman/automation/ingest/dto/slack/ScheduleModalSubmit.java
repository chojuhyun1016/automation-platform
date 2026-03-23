package com.riman.automation.ingest.dto.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Slack 일정등록 Modal Submit 페이로드 파싱 VO
 *
 * <p><b>파싱 대상 block/action ID:</b>
 * <pre>
 *   block_schedule_title        / action_schedule_title        → 제목
 *   block_schedule_description  / action_schedule_description  → 내용
 *   block_schedule_start_date   / action_schedule_start_date   → 시작 날짜 (selected_date)
 *   block_schedule_start_time   / action_schedule_start_time   → 시작 시간 (selected_time)
 *   block_schedule_end_time     / action_schedule_end_time     → 종료 시간 (selected_time)
 *   block_schedule_reminder_1   / action_schedule_reminder_1   → 알림1 (selected_option.value)
 *   block_schedule_reminder_2   / action_schedule_reminder_2   → 알림2
 *   block_schedule_reminder_3   / action_schedule_reminder_3   → 알림3
 *   block_schedule_url          / action_schedule_url          → URL
 * </pre>
 *
 * <p><b>private_metadata:</b> "userId|slackDisplayName|한글이름" (ScheduleModalBuilder 참조)
 * <p>value "0" 은 알림 없음 — reminderMinutes 목록에서 제외됨.
 */
@Getter
public class ScheduleModalSubmit {

    public static final String CALLBACK_ID = "schedule_submit";

    private static final ObjectMapper OM = new ObjectMapper();

    private final String type;          // "view_submission"
    private final String userId;        // Slack User ID (payload.user.id)
    private final String slackUserName; // Slack display name (private_metadata[1])
    private final String koreanName;    // 한글 이름 (private_metadata[2])
    private final String title;
    private final String description;
    private final String startDate;     // yyyy-MM-dd
    private final String startTime;     // HH:mm  (빈 문자열 = 종일)
    private final String endTime;       // HH:mm  (빈 문자열 = startTime 동일)
    private final List<Integer> reminderMinutes; // value=0 제외
    private final String url;

    private ScheduleModalSubmit(JsonNode payload) {
        this.type = payload.path("type").asText("");
        this.userId = payload.path("user").path("id").asText("");

        // private_metadata: "userId|slackDisplayName|한글이름"
        String meta = payload.path("view").path("private_metadata").asText("");
        String[] parts = meta.split("\\|", 3);
        this.slackUserName = parts.length > 1 ? parts[1] : payload.path("user").path("username").asText("");
        this.koreanName = parts.length > 2 ? parts[2] : this.slackUserName;

        JsonNode values = payload.path("view").path("state").path("values");

        this.title = values
                .path("block_schedule_title")
                .path("action_schedule_title")
                .path("value").asText("").trim();

        this.description = values
                .path("block_schedule_description")
                .path("action_schedule_description")
                .path("value").asText("").trim();

        this.startDate = values
                .path("block_schedule_start_date")
                .path("action_schedule_start_date")
                .path("selected_date").asText("").trim();

        this.startTime = values
                .path("block_schedule_start_time")
                .path("action_schedule_start_time")
                .path("selected_time").asText("").trim();

        this.endTime = values
                .path("block_schedule_end_time")
                .path("action_schedule_end_time")
                .path("selected_time").asText("").trim();

        // 알림 드롭다운 3개 — value "0" 은 '없음' 이므로 제외
        this.reminderMinutes = parseReminderDropdowns(values);

        this.url = values
                .path("block_schedule_url")
                .path("action_schedule_url")
                .path("value").asText("").trim();
    }

    public static ScheduleModalSubmit parse(String urlEncodedBody) throws Exception {
        String decoded = URLDecoder.decode(
                urlEncodedBody.substring("payload=".length()), StandardCharsets.UTF_8);
        return new ScheduleModalSubmit(OM.readTree(decoded));
    }

    // =========================================================================
    // 비즈니스 헬퍼
    // =========================================================================

    public boolean isViewSubmission() {
        return "view_submission".equals(type);
    }

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    public boolean hasStartDate() {
        return startDate != null && !startDate.isBlank();
    }

    /**
     * 시작 일시 문자열
     * <pre>
     *   시간 있음: "2026-03-15T14:00"
     *   시간 없음: "2026-03-15"
     * </pre>
     */
    public String getStartDateTime() {
        return (startTime != null && !startTime.isBlank())
                ? startDate + "T" + startTime
                : startDate;
    }

    /**
     * 종료 일시 문자열
     * <pre>
     *   종료시간 있음: "2026-03-15T15:00"
     *   종료시간 없음: null (ScheduleFacade에서 startDateTime + 1시간으로 자동 처리)
     * </pre>
     */
    public String getEndDateTime() {
        if (endTime != null && !endTime.isBlank()) {
            return startDate + "T" + endTime;
        }
        return null; // 미입력 → null 반환 → ScheduleFacade에서 +1시간 자동 설정
    }

    /**
     * 종료 시간 미입력 여부 (Slack 모달 hint 표시 판단용)
     */
    public boolean hasEndTime() {
        return endTime != null && !endTime.isBlank();
    }

    // =========================================================================
    // 내부
    // =========================================================================

    /**
     * 알림 드롭다운 1~3 파싱.
     * static_select → selected_option.value
     * value "0" = 없음 → 결과 목록에서 제외
     */
    private List<Integer> parseReminderDropdowns(JsonNode values) {
        List<Integer> result = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String val = values
                    .path("block_schedule_reminder_" + i)
                    .path("action_schedule_reminder_" + i)
                    .path("selected_option")
                    .path("value").asText("").trim();
            if (val.isBlank() || "0".equals(val)) continue;
            try {
                int minutes = Integer.parseInt(val);
                if (!result.contains(minutes)) { // 중복 제거
                    result.add(minutes);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }
}
