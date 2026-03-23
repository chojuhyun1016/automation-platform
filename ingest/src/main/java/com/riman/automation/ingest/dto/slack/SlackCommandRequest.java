package com.riman.automation.ingest.dto.slack;

import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Slack Slash Command 요청 파싱 결과 VO
 *
 * <p>기존 /재택근무, /부재등록, /계정관리, /일정등록 외에 /현재티켓 커맨드를 추가로 인식한다.
 *
 * <p><b>변경 이력:</b>
 * <ul>
 *   <li>v1: /재택근무 (기존)</li>
 *   <li>v2: /부재등록 추가 (기존)</li>
 *   <li>v3: /계정관리 추가 (기존)</li>
 *   <li>v4: /일정등록 추가 (기존)</li>
 *   <li>v5: /현재티켓 추가 (신규)</li>
 * </ul>
 */
@Getter
public class SlackCommandRequest {

    private static final String REMOTE_WORK_COMMAND = "/재택근무";
    private static final String ABSENCE_COMMAND = "/부재등록";
    private static final String ACCOUNT_MANAGE_COMMAND = "/계정관리";
    private static final String SCHEDULE_COMMAND = "/일정등록";
    private static final String CURRENT_TICKET_COMMAND = "/현재티켓";

    private final String command;
    private final String triggerId;
    private final String userId;
    private final String userName;

    private SlackCommandRequest(Map<String, String> params) {
        this.command = params.getOrDefault("command", "").trim();
        this.triggerId = params.getOrDefault("trigger_id", "");
        this.userId = params.getOrDefault("user_id", "");
        this.userName = params.getOrDefault("user_name", "");
    }

    public static SlackCommandRequest parse(String urlEncodedBody) {
        return new SlackCommandRequest(parseUrlEncoded(urlEncodedBody));
    }

    public boolean isRemoteWorkCommand() {
        return REMOTE_WORK_COMMAND.equals(command);
    }

    public boolean isAbsenceCommand() {
        return ABSENCE_COMMAND.equals(command);
    }

    /**
     * /계정관리 커맨드 여부
     */
    public boolean isAccountManageCommand() {
        return ACCOUNT_MANAGE_COMMAND.equals(command);
    }

    /**
     * /일정등록 커맨드 여부
     */
    public boolean isScheduleCommand() {
        return SCHEDULE_COMMAND.equals(command);
    }

    /**
     * /현재티켓 커맨드 여부
     */
    public boolean isCurrentTicketCommand() {
        return CURRENT_TICKET_COMMAND.equals(command);
    }

    private static Map<String, String> parseUrlEncoded(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = kv.length == 2
                        ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                map.put(key, value);
            } catch (Exception ignored) {
                if (kv.length > 0) map.put(kv[0], kv.length == 2 ? kv[1] : "");
            }
        }
        return map;
    }
}
