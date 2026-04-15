package com.riman.automation.ingest.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 일정 매핑 조회 서비스 (ingest 모듈 전용, 읽기 전용).
 *
 * /일정등록 커맨드 수신 시 본인이 등록한 일정 목록을 조회하여
 * 모달 분기(등록 전용 vs 등록+삭제 통합)에 사용한다.
 * ingest 모듈은 worker 모듈과 독립 Lambda 이므로 worker.service 를 직접 참조할 수 없어
 * 여기에 읽기 전용으로 따로 두었다. 저장/삭제는 worker 의 ScheduleEventMappingService 가 담당한다.
 *
 * 대상 테이블은 환경변수 SCHEDULE_MAPPING_TABLE 로 지정된 DynamoDB 테이블이다.
 * PK 는 slackUserId, SK 는 eventId 이며 calendarId, title, startDateTime, createdAt 속성을 가진다.
 */
@Slf4j
public class ScheduleMappingQueryService {

  private static final String ENV_TABLE = "SCHEDULE_MAPPING_TABLE";

  /**
   * 삭제 드롭다운에 표시할 최대 일정 수.
   * Slack static_select 는 100개까지 허용하지만 UX 를 고려하여 10개로 제한한다.
   */
  private static final int MAX_DISPLAY_COUNT = 10;

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private static final String ATTR_SLACK_USER_ID = "slackUserId";
  private static final String ATTR_EVENT_ID = "eventId";
  private static final String ATTR_CALENDAR_ID = "calendarId";
  private static final String ATTR_TITLE = "title";
  private static final String ATTR_START_DATE_TIME = "startDateTime";

  private final DynamoDbClient dynamoDb;
  private final String tableName;

  public ScheduleMappingQueryService() {
    this.dynamoDb = DynamoDbClient.builder().build();
    this.tableName = System.getenv(ENV_TABLE);

    if (tableName == null || tableName.isBlank()) {
      // 환경변수 미설정 시 예외 대신 경고만 남긴다. 호출 시점에 빈 목록을 반환하는 안전 처리 사용.
      log.warn("[ScheduleMappingQueryService] {} 환경변수 미설정 — 일정 조회 비활성화", ENV_TABLE);
    } else {
      log.info("[ScheduleMappingQueryService] 초기화: table={}", tableName);
      prewarmConnection();
    }
  }

  /**
   * DynamoDB TCP 연결을 사전 수립한다 (Pre-warm).
   *
   * DynamoDB 클라이언트는 초기화 시점에는 TCP 연결을 맺지 않아 첫 쿼리에서
   * TCP handshake + TLS 수립에 약 1500~1800ms 가 소요된다. 이로 인해 콜드스타트 직후
   * /일정등록 커맨드가 Slack 3초 제한을 초과하는 문제가 있었다.
   * INIT 단계에서 dummy DescribeTable 을 호출해 연결을 미리 열어둔다.
   * 실패해도 Lambda 기동을 막지 않으며 첫 실제 쿼리에서 재시도된다.
   */
  private void prewarmConnection() {
    try {
      dynamoDb.describeTable(
          software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest.builder()
              .tableName(tableName)
              .build());
      log.info("[ScheduleMappingQueryService] DynamoDB 연결 사전 수립 완료 (pre-warm)");
    } catch (Exception e) {
      log.warn("[ScheduleMappingQueryService] DynamoDB 사전 연결 실패 (무시): {}", e.getMessage());
    }
  }

  /**
   * slackUserId 로 본인이 등록한 일정 목록을 조회한다.
   *
   * 조회 기준은 오늘(KST) 이후 일정, 날짜 오름차순, 최대 {@value #MAX_DISPLAY_COUNT}개 이다.
   * 환경변수 미설정이나 조회 실패 시 빈 목록을 반환하여 등록 전용 모달로 안전하게 분기되도록 한다.
   */
  public List<MappingEntry> findBySlackUserId(String slackUserId) {
    if (tableName == null || tableName.isBlank()) {
      log.warn("[ScheduleMappingQuery] 테이블 미설정으로 조회 생략: slackUserId={}", slackUserId);
      return new ArrayList<>();
    }
    try {
      // PK(slackUserId) 전체를 조회한다. startDateTime 이 SK 가 아니므로 날짜 필터는 앱 레벨에서 처리한다.
      QueryResponse response = dynamoDb.query(QueryRequest.builder()
          .tableName(tableName)
          .keyConditionExpression("#uid = :uid")
          .expressionAttributeNames(Map.of("#uid", ATTR_SLACK_USER_ID))
          .expressionAttributeValues(Map.of(
              ":uid", AttributeValue.builder().s(slackUserId).build()))
          .build());

      // 현재 시각(KST)과 오늘 날짜 — startDateTime 형식에 따라 비교 기준이 다르다.
      String nowKst = LocalDateTime.now(KST)
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
      String todayStr = LocalDate.now(KST).toString();

      List<MappingEntry> entries = new ArrayList<>();
      for (Map<String, AttributeValue> item : response.items()) {
        String eventId = item.containsKey(ATTR_EVENT_ID)
            ? item.get(ATTR_EVENT_ID).s() : "";
        String calendarId = item.containsKey(ATTR_CALENDAR_ID)
            ? item.get(ATTR_CALENDAR_ID).s() : "";
        String title = item.containsKey(ATTR_TITLE)
            ? item.get(ATTR_TITLE).s() : "";
        String startDateTime = item.containsKey(ATTR_START_DATE_TIME)
            ? item.get(ATTR_START_DATE_TIME).s() : "";

        // 시간 포함 일정은 현재 시각 이후, 종일 일정(날짜만)은 오늘 이후를 기준으로 포함한다.
        boolean future;
        if (startDateTime.contains("T")) {
          String cmp = startDateTime.length() >= 16 ? startDateTime.substring(0, 16) : startDateTime;
          future = cmp.compareTo(nowKst) >= 0;
        } else {
          String dateStr = startDateTime.length() >= 10 ? startDateTime.substring(0, 10) : "";
          future = dateStr.compareTo(todayStr) >= 0;
        }
        if (!future) continue;

        entries.add(new MappingEntry(eventId, calendarId, title, startDateTime));
      }

      // 날짜 오름차순 정렬 후 상위 MAX_DISPLAY_COUNT 개만 반환한다.
      List<MappingEntry> result = entries.stream()
          .sorted(Comparator.comparing(e -> e.startDateTime))
          .limit(MAX_DISPLAY_COUNT)
          .collect(Collectors.toList());

      log.debug("[ScheduleMappingQuery] 목록 조회: slackUserId={}, total={}, filtered={}",
          slackUserId, entries.size(), result.size());
      return result;

    } catch (Exception e) {
      log.error("[ScheduleMappingQuery] 목록 조회 실패: slackUserId={}", slackUserId, e);
      return new ArrayList<>();
    }
  }

  /**
   * 일정 매핑 조회 결과 VO.
   */
  public static class MappingEntry {
    public final String eventId;
    public final String calendarId;
    public final String title;
    public final String startDateTime;

    public MappingEntry(String eventId, String calendarId,
                        String title, String startDateTime) {
      this.eventId = eventId;
      this.calendarId = calendarId;
      this.title = title;
      this.startDateTime = startDateTime;
    }

    /**
     * 모달 드롭다운에 표시할 레이블을 "제목 | 날짜" 형태로 반환한다.
     */
    public String toDisplayLabel() {
      String dateStr = startDateTime != null && startDateTime.length() >= 10
          ? startDateTime.substring(0, 10) : startDateTime;
      return title + " | " + dateStr;
    }
  }
}
