package com.riman.automation.scheduler.service.collect;

import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.scheduler.dto.report.DailyReportData.ScheduleItem;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * /일정등록 커맨드로 등록된 오늘 일정 수집 서비스
 *
 * <p><b>수집 전략:</b>
 * <ol>
 *   <li>DynamoDB {@code ScheduleEventMapping} 테이블에서
 *       {@code slackUserId}로 본인 이벤트 ID 목록을 조회한다.</li>
 *   <li>Google Calendar {@code listEvents}로 오늘 하루 범위의 이벤트를 조회한다.</li>
 *   <li>DynamoDB eventId 집합과 Calendar 이벤트 ID를 교차 매칭하여
 *       /일정등록으로 등록된 이벤트만 추출한다.</li>
 *   <li>이벤트에서 {@code endDateTime}, {@code url}을 직접 파싱한다.</li>
 * </ol>
 *
 * <p><b>데이터 소스 분리 이유:</b>
 * DynamoDB에는 {@code startDateTime}만 저장되어 있고
 * {@code endDateTime}·{@code url}은 저장되지 않는다.
 * Google Calendar 이벤트가 원본이므로 Calendar API에서 직접 가져온다.
 *
 * <p><b>URL 파싱 규칙:</b>
 * worker {@code ScheduleFacade.buildDescription()}이 description 마지막에
 * {@code "🔗 https://..."} 형식으로 URL을 기록한다.
 * {@code "🔗 "} 접두사로 시작하는 줄을 찾아 URL로 사용한다.
 *
 * <p><b>오늘 일정 기준:</b>
 * 이벤트의 startDate(종일) 또는 startDateTime(시간 지정)이 baseDate(KST 오늘)인 것.
 *
 * <p><b>정렬:</b>
 * 종일 일정({@code allDay=true}) → 시간 지정 일정 ({@code startTime} 오름차순)
 *
 * <p><b>환경변수:</b> {@code SCHEDULE_MAPPING_TABLE} 미설정 시 빈 목록 반환 (보고서 계속 발송).
 */
@Slf4j
@RequiredArgsConstructor
public class DailyScheduleCollector {

    private static final String ENV_TABLE = "SCHEDULE_MAPPING_TABLE";

    private static final String ATTR_SLACK_USER_ID = "slackUserId";
    private static final String ATTR_EVENT_ID = "eventId";

    /**
     * Google Calendar description에서 URL을 식별하는 접두사
     */
    private static final String URL_PREFIX = "🔗 ";

    private final DynamoDbClient dynamoDb;
    private final GoogleCalendarClient calendarClient;

    /**
     * 팀원 전체의 오늘 일정을 한꺼번에 수집.
     *
     * <p>캘린더를 1회만 조회하고 DynamoDB eventId 교차 매칭으로 팀원별 분류한다.
     *
     * @param scheduleCalendarId 일정 캘린더 ID (DailyReportConfig.scheduleCalendarId)
     * @param members            팀원 목록
     * @param baseDate           기준일 (KST 오늘)
     * @return 팀원 slackUserId → 오늘 ScheduleItem 목록 (정렬 완료)
     */
    public Map<String, List<ScheduleItem>> collectAllMembers(
            String scheduleCalendarId,
            List<TeamMember> members,
            LocalDate baseDate) {

        // 결과 맵 초기화 (모든 팀원 빈 목록)
        Map<String, List<ScheduleItem>> result = new LinkedHashMap<>();
        members.forEach(m -> result.put(m.getSlackUserId(), new ArrayList<>()));

        String tableName = System.getenv(ENV_TABLE);
        if (tableName == null || tableName.isBlank()) {
            log.warn("[ScheduleCollect] SCHEDULE_MAPPING_TABLE 미설정 → 오늘 일정 수집 생략");
            return result;
        }

        if (scheduleCalendarId == null || scheduleCalendarId.isBlank()) {
            log.warn("[ScheduleCollect] schedule_calendar_id 미설정 → 오늘 일정 수집 생략");
            return result;
        }

        // ── 1) 캘린더에서 오늘 하루 이벤트 전체 조회 (1회) ───────────────────
        List<Event> todayEvents = fetchTodayEvents(scheduleCalendarId, baseDate);
        if (todayEvents.isEmpty()) {
            log.info("[ScheduleCollect] 오늘 캘린더 이벤트 없음: date={}", baseDate);
            return result;
        }

        // 캘린더 이벤트를 eventId로 빠르게 찾기 위한 맵
        Map<String, Event> eventById = new HashMap<>();
        for (Event e : todayEvents) {
            eventById.put(e.getId(), e);
        }
        log.info("[ScheduleCollect] 오늘 캘린더 이벤트: {}건", todayEvents.size());

        // ── 2) 팀원별 DynamoDB 조회 → 캘린더 이벤트 교차 매칭 ───────────────
        for (TeamMember member : members) {
            String slackUserId = member.getSlackUserId();
            if (slackUserId == null || slackUserId.isBlank()) continue;

            try {
                Set<String> myEventIds = queryEventIds(tableName, slackUserId);
                if (myEventIds.isEmpty()) continue;

                List<ScheduleItem> items = new ArrayList<>();
                for (String eventId : myEventIds) {
                    Event event = eventById.get(eventId);
                    if (event == null) continue; // 오늘 이벤트 아님

                    ScheduleItem item = parseScheduleItem(event, baseDate);
                    if (item != null) items.add(item);
                }

                // 정렬: 종일 우선 → startTime 오름차순
                items.sort(Comparator
                        .comparing((ScheduleItem s) -> s.isAllDay() ? 0 : 1)
                        .thenComparing(s -> s.getStartTime() == null
                                ? LocalTime.MIN : s.getStartTime()));

                result.put(slackUserId, items);
                log.info("[ScheduleCollect] 팀원 일정 수집: member={}, {}건",
                        member.getName(), items.size());

            } catch (Exception e) {
                log.error("[ScheduleCollect] 팀원 일정 수집 실패: member={}, slackUserId={}",
                        member.getName(), slackUserId, e);
                // 개별 팀원 실패는 빈 목록으로 처리 — 보고서 발송 계속
            }
        }

        return result;
    }

    // =========================================================================
    // 내부 — DynamoDB
    // =========================================================================

    /**
     * DynamoDB에서 slackUserId로 등록된 eventId 집합을 조회.
     *
     * @param tableName   ScheduleEventMapping 테이블명
     * @param slackUserId 조회 대상
     * @return Google Calendar eventId 집합
     */
    private Set<String> queryEventIds(String tableName, String slackUserId) {
        try {
            QueryResponse response = dynamoDb.query(QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#uid = :uid")
                    .expressionAttributeNames(Map.of("#uid", ATTR_SLACK_USER_ID))
                    .expressionAttributeValues(Map.of(
                            ":uid", AttributeValue.builder().s(slackUserId).build()))
                    .projectionExpression(ATTR_EVENT_ID) // eventId만 가져오면 충분
                    .build());

            Set<String> ids = new HashSet<>();
            for (Map<String, AttributeValue> item : response.items()) {
                if (item.containsKey(ATTR_EVENT_ID)) {
                    ids.add(item.get(ATTR_EVENT_ID).s());
                }
            }
            log.debug("[ScheduleCollect] DynamoDB 조회: slackUserId={}, eventIds={}건",
                    slackUserId, ids.size());
            return ids;

        } catch (Exception e) {
            log.warn("[ScheduleCollect] DynamoDB 조회 실패: slackUserId={}, err={}",
                    slackUserId, e.getMessage());
            return Set.of();
        }
    }

    // =========================================================================
    // 내부 — Google Calendar
    // =========================================================================

    /**
     * 오늘 하루(baseDate 00:00 ~ 23:59:59 KST) 이벤트 목록 조회.
     */
    private List<Event> fetchTodayEvents(String calendarId, LocalDate baseDate) {
        String timeMin = baseDate + "T00:00:00+09:00";
        String timeMax = baseDate + "T23:59:59+09:00";
        try {
            List<Event> events = calendarClient.listEvents(calendarId, timeMin, timeMax, null);
            log.info("[ScheduleCollect] Calendar 조회: calendarId={}, date={}, {}건",
                    calendarId, baseDate, events.size());
            return events;
        } catch (Exception e) {
            log.error("[ScheduleCollect] Calendar 조회 실패: calendarId={}, err={}",
                    calendarId, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // 내부 — 이벤트 파싱
    // =========================================================================

    /**
     * Google Calendar {@link Event} → {@link ScheduleItem} 변환.
     *
     * <p>startDate(종일) 또는 startDateTime(시간 지정) 기준으로 allDay/startTime/endTime 설정.
     *
     * @return 파싱 성공 시 ScheduleItem, 파싱 불가 시 null
     */
    private ScheduleItem parseScheduleItem(Event event, LocalDate baseDate) {
        if (event == null || event.getStart() == null) return null;

        String rawTitle = event.getSummary() != null ? event.getSummary() : "";
        String title = rawTitle.startsWith("[일정] ") ? rawTitle.substring(5) : rawTitle;
        String url = extractUrl(event.getDescription());

        // ── 종일 이벤트 ──────────────────────────────────────────────────────
        if (event.getStart().getDate() != null) {
            return ScheduleItem.builder()
                    .title(title)
                    .allDay(true)
                    .startTime(null)
                    .endTime(null)
                    .url(url)
                    .build();
        }

        // ── 시간 지정 이벤트 ─────────────────────────────────────────────────
        if (event.getStart().getDateTime() != null) {
            LocalTime startTime = parseTime(event.getStart().getDateTime().toStringRfc3339());
            LocalTime endTime = (event.getEnd() != null && event.getEnd().getDateTime() != null)
                    ? parseTime(event.getEnd().getDateTime().toStringRfc3339())
                    : null;

            return ScheduleItem.builder()
                    .title(title)
                    .allDay(false)
                    .startTime(startTime)
                    .endTime(endTime)
                    .url(url)
                    .build();
        }

        log.debug("[ScheduleCollect] start 정보 없음, 건너뜀: title={}", title);
        return null;
    }

    /**
     * RFC3339 날짜시간 문자열에서 KST LocalTime 파싱.
     *
     * <p>Google Calendar API가 반환하는 datetime은
     * {@code 2026-03-08T09:00:00+09:00} 또는 {@code 2026-03-08T00:00:00Z} 형식.
     *
     * <p>KST(+09:00) 기준으로 시·분을 추출한다.
     * Z(UTC) 형식이면 9시간을 더해 KST로 변환한다.
     */
    private LocalTime parseTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.length() < 16) return null;
        try {
            // "+09:00" 또는 "Z" 오프셋 처리
            // rfc3339 예시: "2026-03-08T09:00:00+09:00"  → HH:mm 부분: substring(11, 16)
            //               "2026-03-08T00:00:00Z"        → UTC이므로 +9h 필요
            String timePart = rfc3339.substring(11, 16); // "HH:mm"
            int hour = Integer.parseInt(timePart.substring(0, 2));
            int minute = Integer.parseInt(timePart.substring(3, 5));

            // Z(UTC) 오프셋 처리 — KST = UTC + 9
            if (rfc3339.endsWith("Z") || rfc3339.contains("+00:00")) {
                hour = (hour + 9) % 24;
            }

            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            log.warn("[ScheduleCollect] 시간 파싱 실패: rfc3339={}", rfc3339);
            return null;
        }
    }

    /**
     * Google Calendar 이벤트 description에서 URL 파싱.
     *
     * <p>worker {@code ScheduleFacade.buildDescription()}이 생성하는 형식:
     * <pre>
     *   📝 등록자: 홍길동 (@hong)
     *   🕐 시간: 2026-03-08 09:00 ~ 2026-03-08 10:00
     *
     *   회의 내용 메모
     *
     *   🔗 https://meet.google.com/abc-defg-hij
     * </pre>
     * <p>
     * {@code "🔗 "} 접두사로 시작하는 줄에서 URL을 추출한다.
     *
     * @param description 이벤트 본문 (null 가능)
     * @return URL 문자열, 없으면 null
     */
    private String extractUrl(String description) {
        if (description == null || description.isBlank()) return null;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(URL_PREFIX)) {
                String url = trimmed.substring(URL_PREFIX.length()).trim();
                return url.isBlank() ? null : url;
            }
        }
        return null;
    }
}
