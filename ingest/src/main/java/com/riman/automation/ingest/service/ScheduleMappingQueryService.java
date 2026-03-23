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
 * 일정 매핑 조회 서비스 (ingest 모듈 — 읽기 전용)
 *
 * <p><b>역할:</b>
 * /일정등록 커맨드 수신 시 본인이 등록한 일정 목록을 조회하여
 * 모달 분기(등록 전용 vs 등록+삭제 통합)에 사용한다.
 *
 * <p><b>왜 ingest.service 인가:</b>
 * ingest 모듈은 worker 모듈과 독립된 Lambda 이므로 worker.service 를 직접 참조할 수 없다.
 * AccountManageFacade 가 GroupwareCredentialService(ingest.service) 를 사용하는 것과
 * 동일한 패턴. 저장/삭제는 Worker 의 ScheduleEventMappingService 가 담당한다.
 *
 * <p><b>테이블:</b> ScheduleEventMapping (환경변수: SCHEDULE_MAPPING_TABLE)
 * <pre>
 *   PK: slackUserId (String)
 *   SK: eventId     (String)
 *   속성: calendarId, title, startDateTime, createdAt
 * </pre>
 */
@Slf4j
public class ScheduleMappingQueryService {

    private static final String ENV_TABLE = "SCHEDULE_MAPPING_TABLE";

    /**
     * 삭제 드롭다운에 표시할 최대 일정 수 (Slack static_select 100개 제한 + UX 고려)
     */
    private static final int MAX_DISPLAY_COUNT = 10;

    /**
     * KST 기준 날짜 비교
     */
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
            // 생성자에서 예외를 던지면 Lambda 기동 자체가 실패함
            // 환경변수 미설정 시 경고만 남기고, 실제 호출 시점에 빈 목록 반환으로 안전하게 처리
            log.warn("[ScheduleMappingQueryService] {} 환경변수 미설정 — 일정 조회 비활성화", ENV_TABLE);
        } else {
            log.info("[ScheduleMappingQueryService] 초기화: table={}", tableName);
            // ── DynamoDB 연결 사전 수립 (Pre-warm Connection) ──────────────────
            // DynamoDB 클라이언트는 초기화 시점에 실제 TCP 연결을 맺지 않음.
            // 첫 쿼리 실행 시 TCP handshake + TLS 수립에 약 1500~1800ms 소요됨.
            // 이로 인해 콜드스타트 직후 /일정등록 커맨드가 Slack 3초 제한을 초과함.
            // INIT 단계에서 dummy DescribeTable로 연결을 미리 수립하여 첫 쿼리 지연을 제거.
            prewarmConnection();
        }
    }

    /**
     * DynamoDB TCP 연결 사전 수립.
     *
     * <p>Lambda INIT 단계에서 호출되어 DynamoDB와의 TCP+TLS 연결을 미리 맺어둔다.
     * 실제 데이터 조회 없이 DescribeTable 메타데이터 요청만 수행하므로 비용 없음.
     * 실패해도 Lambda 기동을 막지 않으며, 첫 실제 쿼리에서 정상 연결된다.
     */
    private void prewarmConnection() {
        try {
            dynamoDb.describeTable(
                    software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest.builder()
                            .tableName(tableName)
                            .build());
            log.info("[ScheduleMappingQueryService] DynamoDB 연결 사전 수립 완료 (pre-warm)");
        } catch (Exception e) {
            // 연결 실패해도 Lambda 기동은 정상 진행 — 첫 실제 쿼리에서 재시도됨
            log.warn("[ScheduleMappingQueryService] DynamoDB 사전 연결 실패 (무시): {}", e.getMessage());
        }
    }

    /**
     * slackUserId 로 본인이 등록한 일정 목록을 조회한다.
     *
     * <p><b>조회 기준:</b>
     * <ul>
     *   <li>오늘(KST) 이후 일정만 표시 — 이미 지난 일정은 삭제 불필요</li>
     *   <li>날짜 오름차순 정렬 — 가장 임박한 일정이 위에 표시</li>
     *   <li>최대 {@value #MAX_DISPLAY_COUNT}개 — Slack static_select 제한 + UX</li>
     * </ul>
     *
     * @param slackUserId 조회 대상 Slack User ID
     * @return 매핑 목록 (없으면 빈 리스트)
     */
    public List<MappingEntry> findBySlackUserId(String slackUserId) {
        // 환경변수 미설정 시 빈 목록 반환 → 등록 전용 모달로 분기 (안전한 fallback)
        if (tableName == null || tableName.isBlank()) {
            log.warn("[ScheduleMappingQuery] 테이블 미설정으로 조회 생략: slackUserId={}", slackUserId);
            return new ArrayList<>();
        }
        try {
            // DynamoDB Query — PK(slackUserId) 전체 조회
            // 날짜 필터는 애플리케이션 레벨에서 처리 (startDateTime이 SK가 아니므로)
            QueryResponse response = dynamoDb.query(QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#uid = :uid")
                    .expressionAttributeNames(Map.of("#uid", ATTR_SLACK_USER_ID))
                    .expressionAttributeValues(Map.of(
                            ":uid", AttributeValue.builder().s(slackUserId).build()))
                    .build());

            // 현재 시각(KST) — "yyyy-MM-ddTHH:mm" 형태로 startDateTime 과 비교
            // startDateTime이 현재 시각보다 크거나 같은 일정만 포함
            String nowKst = LocalDateTime.now(KST)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            // 종일 일정(startDateTime = "yyyy-MM-dd")은 오늘 날짜 포함
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

                // 시간 포함 일정: 현재 시각 이후만 포함
                // 종일 일정(날짜만): 오늘 이후 포함
                boolean future;
                if (startDateTime.contains("T")) {
                    // "yyyy-MM-ddTHH:mm..." 형태 — 현재 시각과 직접 비교
                    String cmp = startDateTime.length() >= 16 ? startDateTime.substring(0, 16) : startDateTime;
                    future = cmp.compareTo(nowKst) >= 0;
                } else {
                    // "yyyy-MM-dd" 종일 일정 — 오늘 포함
                    String dateStr = startDateTime.length() >= 10 ? startDateTime.substring(0, 10) : "";
                    future = dateStr.compareTo(todayStr) >= 0;
                }
                if (!future) continue;

                entries.add(new MappingEntry(eventId, calendarId, title, startDateTime));
            }

            // 날짜 오름차순 정렬 후 최대 MAX_DISPLAY_COUNT 개만 반환
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

    // =========================================================================
    // 반환 타입
    // =========================================================================

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
         * 모달 드롭다운 표시용 레이블 — "제목 | 날짜" 형태
         */
        public String toDisplayLabel() {
            String dateStr = startDateTime != null && startDateTime.length() >= 10
                    ? startDateTime.substring(0, 10) : startDateTime;
            return title + " | " + dateStr;
        }
    }
}
