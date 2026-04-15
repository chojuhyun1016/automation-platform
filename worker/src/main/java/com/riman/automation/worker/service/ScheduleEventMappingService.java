package com.riman.automation.worker.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 일정등록과 Google Calendar 이벤트 ID 매핑 서비스 (worker 전용).
 * (slackUserId, eventId)를 복합 키로 사용하여 일정 삭제 시 소유권 확인을 수행한다.
 * 보고서 집계를 위해 slackUserName과 koreanName 등 표시용 메타데이터도 함께 저장한다.
 */
@Slf4j
public class ScheduleEventMappingService {

  private static final String ENV_TABLE = "SCHEDULE_MAPPING_TABLE";

  private static final String ATTR_SLACK_USER_ID = "slackUserId";
  private static final String ATTR_EVENT_ID = "eventId";
  private static final String ATTR_CALENDAR_ID = "calendarId";
  private static final String ATTR_TITLE = "title";
  private static final String ATTR_START_DATE_TIME = "startDateTime";
  private static final String ATTR_END_DATE_TIME = "endDateTime";
  private static final String ATTR_SLACK_USER_NAME = "slackUserName";
  private static final String ATTR_KOREAN_NAME = "koreanName";
  private static final String ATTR_CREATED_AT = "createdAt";

  private final DynamoDbClient dynamoDb;
  private final String tableName;

  public ScheduleEventMappingService() {
    this.dynamoDb = DynamoDbClient.builder().build();
    this.tableName = System.getenv(ENV_TABLE);

    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException(ENV_TABLE + " 환경변수 미설정");
    }
    log.info("[ScheduleEventMappingService] 초기화: table={}", tableName);
  }

  /**
   * 일정 등록 매핑을 저장한다. 저장 실패는 로그만 남기고 전파하지 않는다.
   *
   * @param slackUserId   요청자 Slack User ID
   * @param slackUserName Slack display name (보고서 활용)
   * @param koreanName    한글 이름 (보고서 활용)
   * @param eventId       Google Calendar Event ID
   * @param calendarId    Google Calendar ID
   * @param title         이벤트 제목 ("[일정]" prefix 포함)
   * @param startDateTime 시작 일시 문자열
   * @param endDateTime   종료 일시 문자열 (null 허용)
   */
  public void saveMapping(String slackUserId, String slackUserName, String koreanName,
                          String eventId, String calendarId,
                          String title, String startDateTime, String endDateTime) {
    try {
      Map<String, AttributeValue> item = new HashMap<>();
      item.put(ATTR_SLACK_USER_ID, s(slackUserId));
      item.put(ATTR_EVENT_ID, s(eventId));
      item.put(ATTR_CALENDAR_ID, s(calendarId));
      item.put(ATTR_TITLE, s(title));
      item.put(ATTR_START_DATE_TIME, s(startDateTime));
      item.put(ATTR_END_DATE_TIME, s(endDateTime));
      item.put(ATTR_SLACK_USER_NAME, s(slackUserName));
      item.put(ATTR_KOREAN_NAME, s(koreanName));
      item.put(ATTR_CREATED_AT, AttributeValue.builder()
          .n(String.valueOf(System.currentTimeMillis())).build());

      dynamoDb.putItem(PutItemRequest.builder()
          .tableName(tableName)
          .item(item)
          .build());

      log.info("[ScheduleMapping] 저장: slackUserId={}, koreanName={}, eventId={}, title={}",
          slackUserId, koreanName, eventId, title);

    } catch (Exception e) {
      log.error("[ScheduleMapping] 저장 실패: slackUserId={}, eventId={}",
          slackUserId, eventId, e);
    }
  }

  /**
   * 단건 매핑을 조회한다. 일정 삭제 처리 전 소유권 확인에 사용된다.
   */
  public MappingEntry findMapping(String slackUserId, String eventId) {
    try {
      GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
          .tableName(tableName)
          .key(buildKey(slackUserId, eventId))
          .build());

      if (!response.hasItem() || response.item().isEmpty()) return null;

      Map<String, AttributeValue> item = response.item();
      return new MappingEntry(
          slackUserId, eventId,
          str(item, ATTR_CALENDAR_ID),
          str(item, ATTR_TITLE),
          str(item, ATTR_START_DATE_TIME)
      );
    } catch (Exception e) {
      log.error("[ScheduleMapping] 단건 조회 실패: slackUserId={}, eventId={}",
          slackUserId, eventId, e);
      return null;
    }
  }

  /**
   * 매핑을 삭제한다. 삭제 실패는 로그만 남기고 전파하지 않는다.
   */
  public void deleteMapping(String slackUserId, String eventId) {
    try {
      dynamoDb.deleteItem(DeleteItemRequest.builder()
          .tableName(tableName)
          .key(buildKey(slackUserId, eventId))
          .build());
      log.info("[ScheduleMapping] 삭제: slackUserId={}, eventId={}", slackUserId, eventId);
    } catch (Exception e) {
      log.error("[ScheduleMapping] 삭제 실패: slackUserId={}, eventId={}",
          slackUserId, eventId, e);
    }
  }

  private Map<String, AttributeValue> buildKey(String slackUserId, String eventId) {
    return Map.of(
        ATTR_SLACK_USER_ID, s(slackUserId),
        ATTR_EVENT_ID, s(eventId)
    );
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v != null ? v : "").build();
  }

  private static String str(Map<String, AttributeValue> item, String key) {
    return item.containsKey(key) ? item.get(key).s() : "";
  }

  /** DynamoDB 매핑 조회 결과 VO. */
  public static class MappingEntry {
    public final String slackUserId;
    public final String eventId;
    public final String calendarId;
    public final String title;
    public final String startDateTime;

    public MappingEntry(String slackUserId, String eventId, String calendarId,
                        String title, String startDateTime) {
      this.slackUserId = slackUserId;
      this.eventId = eventId;
      this.calendarId = calendarId;
      this.title = title;
      this.startDateTime = startDateTime;
    }

    /**
     * Slack 메시지 등에서 사람이 읽기 좋은 라벨을 반환한다.
     */
    public String toDisplayLabel() {
      String dateStr = startDateTime != null && startDateTime.length() >= 10
          ? startDateTime.substring(0, 10) : startDateTime;
      return title + " | " + dateStr;
    }
  }
}
