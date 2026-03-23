package com.riman.automation.worker.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Jira 이슈 ↔ Google Calendar 이벤트 ID 매핑 서비스
 *
 * <p><b>도입 배경:</b>
 * Google Calendar API events.list()의 기본 응답에 extendedProperties가 포함되지 않아
 * findJiraEventByIssueKey()에서 항상 null 반환 → 중복 이벤트 생성 버그.
 * DynamoDB에 issueKey → calendarEventId 매핑을 저장하여
 * Google Calendar API 응답 구조에 독립적인 방식으로 해결한다.
 *
 * <p><b>테이블 설계:</b>
 * <pre>
 *   테이블명: JiraCalendarEventMapping  (환경변수: CALENDAR_MAPPING_TABLE)
 *   PK: issueKey  (String) — 예: CCE-2339
 *   SK: calendarId (String) — 예: xxxx@group.calendar.google.com
 *   속성:
 *     eventId      (String) — Google Calendar Event ID
 *     assigneeName (String) — 마지막 저장 시점의 팀원 담당자 이름
 *     createdAt    (Number) — epoch millis
 *     updatedAt    (Number) — epoch millis
 * </pre>
 *
 * <p><b>기존 extendedProperties 방식과의 관계:</b>
 * CalendarService의 extendedProperties 저장 코드는 그대로 유지한다.
 * 조회만 DynamoDB로 대체하므로 기존 캘린더 이벤트에는 영향 없음.
 * 단, 이전에 생성된 이벤트는 DynamoDB에 매핑이 없으므로
 * findMapping()이 null을 반환 → CalendarService가 extendedProperties fallback 조회.
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

    // =========================================================================
    // 조회
    // =========================================================================

    /**
     * issueKey + calendarId 로 매핑된 이벤트 정보를 조회한다.
     *
     * @param issueKey   Jira 이슈 키 (예: CCE-2339)
     * @param calendarId Google Calendar ID
     * @return 매핑 정보, 없으면 null
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
            return null;  // 조회 실패 시 null → CalendarService가 fallback 처리
        }
    }

    // =========================================================================
    // 저장 / 업데이트
    // =========================================================================

    /**
     * issueKey + calendarId → eventId 매핑을 저장(upsert)한다.
     *
     * <p>이벤트 생성 시(CREATE), 이벤트 수정 시(UPDATE) 모두 이 메서드로 저장한다.
     * 이미 존재하면 eventId, assigneeName, updatedAt 을 덮어쓴다.
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

            // createdAt은 최초 삽입 시만 설정 (조건부 표현식으로 덮어쓰지 않음)
            // → attribute_not_exists(createdAt) 조건으로 처리
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
            // 저장 실패는 캘린더 처리 결과에 영향 주지 않음 — 로그만 남김
        }
    }

    // =========================================================================
    // 삭제
    // =========================================================================

    /**
     * 매핑 항목을 삭제한다.
     *
     * <p>Jira 이슈 삭제(jira:issue_deleted) 또는 마감일 제거 시 호출.
     *
     * @param issueKey   Jira 이슈 키
     * @param calendarId Google Calendar ID
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

    // =========================================================================
    // 내부
    // =========================================================================

    private Map<String, AttributeValue> buildKey(String issueKey, String calendarId) {
        return Map.of(
                ATTR_ISSUE_KEY, AttributeValue.builder().s(issueKey).build(),
                ATTR_CALENDAR_ID, AttributeValue.builder().s(calendarId).build()
        );
    }

    // =========================================================================
    // 반환 타입
    // =========================================================================

    /**
     * DynamoDB 매핑 조회 결과 VO.
     */
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
