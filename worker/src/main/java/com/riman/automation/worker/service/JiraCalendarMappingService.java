package com.riman.automation.worker.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Jira 이슈와 Google Calendar 이벤트 ID 매핑 서비스.
 * Google Calendar API events.list()가 extendedProperties를 기본 응답에 포함하지 않아
 * issueKey 기반 조회가 실패하던 문제를 해결하기 위해 DynamoDB에 (issueKey, calendarId) → eventId 매핑을 저장한다.
 * CalendarService는 매핑 조회 실패 시 기존 extendedProperties 스캔 방식으로 폴백한다.
 */
@Slf4j
public class JiraCalendarMappingService {

  private static final String ENV_TABLE = "CALENDAR_MAPPING_TABLE";

  private static final String ATTR_ISSUE_KEY = "issueKey";
  private static final String ATTR_CALENDAR_ID = "calendarId";
  private static final String ATTR_EVENT_ID = "eventId";
  private static final String ATTR_ASSIGNEE = "assigneeName";
  private static final String ATTR_CREATED_AT = "createdAt";
  private static final String ATTR_UPDATED_AT = "updatedAt";

  private final DynamoDbClient dynamoDb;
  private final String tableName;

  public JiraCalendarMappingService() {
    this.dynamoDb = DynamoDbClient.builder().build();
    this.tableName = System.getenv(ENV_TABLE);

    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException(ENV_TABLE + " 환경변수 미설정");
    }
    log.info("[JiraCalendarMappingService] 초기화: table={}", tableName);
  }

  /**
   * issueKey와 calendarId로 매핑된 Calendar Event 정보를 조회한다.
   * 조회 실패 시 null을 반환하여 CalendarService가 extendedProperties 폴백 경로로 진입하도록 한다.
   *
   * @param issueKey   Jira 이슈 키 (예: CCE-2339)
   * @param calendarId Google Calendar ID
   * @return 매핑 엔트리. 없거나 조회 실패 시 null
   */
  public MappingEntry findMapping(String issueKey, String calendarId) {
    try {
      GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
          .tableName(tableName)
          .key(buildKey(issueKey, calendarId))
          .build());

      if (!response.hasItem() || response.item().isEmpty()) {
        log.debug("[Mapping] 없음: issueKey={}, calendarId={}", issueKey, calendarId);
        return null;
      }

      Map<String, AttributeValue> item = response.item();
      String eventId = item.containsKey(ATTR_EVENT_ID)
          ? item.get(ATTR_EVENT_ID).s() : null;
      String assigneeName = item.containsKey(ATTR_ASSIGNEE)
          ? item.get(ATTR_ASSIGNEE).s() : "";

      log.debug("[Mapping] 조회: issueKey={}, eventId={}, assignee={}",
          issueKey, eventId, assigneeName);
      return new MappingEntry(issueKey, calendarId, eventId, assigneeName);

    } catch (Exception e) {
      log.error("[Mapping] 조회 실패: issueKey={}, calendarId={}", issueKey, calendarId, e);
      return null;
    }
  }

  /**
   * (issueKey, calendarId) → eventId 매핑을 upsert한다.
   * createdAt은 if_not_exists 조건식으로 최초 1회만 기록한다. 저장 실패는 로그만 남기고 전파하지 않는다.
   *
   * @param issueKey     Jira 이슈 키
   * @param calendarId   Google Calendar ID
   * @param eventId      Google Calendar Event ID
   * @param assigneeName 현재 팀원 담당자 이름 (비팀원→팀원 전환 포함)
   */
  public void saveMapping(String issueKey, String calendarId,
                          String eventId, String assigneeName) {
    try {
      long now = System.currentTimeMillis();

      Map<String, AttributeValue> item = new HashMap<>(buildKey(issueKey, calendarId));
      item.put(ATTR_EVENT_ID, AttributeValue.builder().s(eventId).build());
      item.put(ATTR_ASSIGNEE, AttributeValue.builder()
          .s(assigneeName != null ? assigneeName : "").build());
      item.put(ATTR_UPDATED_AT, AttributeValue.builder().n(String.valueOf(now)).build());

      dynamoDb.updateItem(UpdateItemRequest.builder()
          .tableName(tableName)
          .key(buildKey(issueKey, calendarId))
          .updateExpression(
              "SET #eid = :eid, #an = :an, #ua = :ua, " +
                  "#ca = if_not_exists(#ca, :ua)")
          .expressionAttributeNames(Map.of(
              "#eid", ATTR_EVENT_ID,
              "#an", ATTR_ASSIGNEE,
              "#ua", ATTR_UPDATED_AT,
              "#ca", ATTR_CREATED_AT))
          .expressionAttributeValues(Map.of(
              ":eid", AttributeValue.builder().s(eventId).build(),
              ":an", AttributeValue.builder()
                  .s(assigneeName != null ? assigneeName : "").build(),
              ":ua", AttributeValue.builder().n(String.valueOf(now)).build()))
          .build());

      log.info("[Mapping] 저장: issueKey={}, calendarId={}, eventId={}, assignee={}",
          issueKey, calendarId, eventId, assigneeName);

    } catch (Exception e) {
      log.error("[Mapping] 저장 실패: issueKey={}, eventId={}", issueKey, eventId, e);
    }
  }

  /**
   * 매핑 항목을 삭제한다. Jira 이슈 삭제 또는 마감일 제거 시 호출된다.
   */
  public void deleteMapping(String issueKey, String calendarId) {
    try {
      dynamoDb.deleteItem(DeleteItemRequest.builder()
          .tableName(tableName)
          .key(buildKey(issueKey, calendarId))
          .build());
      log.info("[Mapping] 삭제: issueKey={}, calendarId={}", issueKey, calendarId);
    } catch (Exception e) {
      log.error("[Mapping] 삭제 실패: issueKey={}, calendarId={}", issueKey, e);
    }
  }

  private Map<String, AttributeValue> buildKey(String issueKey, String calendarId) {
    return Map.of(
        ATTR_ISSUE_KEY, AttributeValue.builder().s(issueKey).build(),
        ATTR_CALENDAR_ID, AttributeValue.builder().s(calendarId).build()
    );
  }

  /** DynamoDB 매핑 조회 결과 VO. */
  public static class MappingEntry {
    public final String issueKey;
    public final String calendarId;
    public final String eventId;
    public final String assigneeName;

    public MappingEntry(String issueKey, String calendarId,
                        String eventId, String assigneeName) {
      this.issueKey = issueKey;
      this.calendarId = calendarId;
      this.eventId = eventId;
      this.assigneeName = assigneeName;
    }
  }
}
