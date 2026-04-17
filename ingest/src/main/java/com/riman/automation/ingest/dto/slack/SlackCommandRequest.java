package com.riman.automation.ingest.dto.slack;

import lombok.Getter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Slack Slash Command 요청 파싱 결과 VO.
 * 지원 커맨드: /재택근무, /부재등록, /계정관리, /일정등록, /현재티켓, /점심카드.
 */
@Getter
public class SlackCommandRequest {

  private static final String REMOTE_WORK_COMMAND = "/재택근무";
  private static final String ABSENCE_COMMAND = "/부재등록";
  private static final String ACCOUNT_MANAGE_COMMAND = "/계정관리";
  private static final String SCHEDULE_COMMAND = "/일정등록";
  private static final String CURRENT_TICKET_COMMAND = "/현재티켓";
  private static final String LUNCH_CARD_COMMAND = "/점심카드";

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

  /**
   * URL-encoded body를 파싱하여 요청 객체를 생성한다.
   */
  public static SlackCommandRequest parse(String urlEncodedBody) {
    return new SlackCommandRequest(parseUrlEncoded(urlEncodedBody));
  }

  public boolean isRemoteWorkCommand() {
    return REMOTE_WORK_COMMAND.equals(command);
  }

  public boolean isAbsenceCommand() {
    return ABSENCE_COMMAND.equals(command);
  }

  public boolean isAccountManageCommand() {
    return ACCOUNT_MANAGE_COMMAND.equals(command);
  }

  public boolean isScheduleCommand() {
    return SCHEDULE_COMMAND.equals(command);
  }

  public boolean isCurrentTicketCommand() {
    return CURRENT_TICKET_COMMAND.equals(command);
  }

  public boolean isLunchCardCommand() {
    return LUNCH_CARD_COMMAND.equals(command);
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
