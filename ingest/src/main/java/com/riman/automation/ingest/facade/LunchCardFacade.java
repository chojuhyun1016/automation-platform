package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.ingest.dto.slack.LunchCardModalSubmit;
import com.riman.automation.ingest.payload.LunchCardModalBuilder;
import com.riman.automation.ingest.payload.LunchCardModalBuilder.Status;
import com.riman.automation.ingest.payload.LunchCardModalBuilder.ViewData;
import com.riman.automation.ingest.service.SlackApiService;
import com.riman.automation.ingest.service.WorkerMessageService;
import com.riman.automation.ingest.util.HttpResponse;
import com.riman.automation.common.util.DateTimeUtil;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /점심카드 커맨드 처리 Facade.
 * 모달 오픈, 모달 submit (SQS 위임), block_actions 3개 진입점을 제공한다.
 *
 * 설계 원칙: handleCommand() 에서 Calendar 조회 후 모달을 빌드한다.
 * GoogleCalendarClient 는 static volatile 캐싱으로 콜드스타트를 완화한다.
 */
@Slf4j
public class LunchCardFacade {

  static final String SLASH_COMMAND = "/점심카드";
  static final String CALLBACK_ID = "lunch_card_submit";
  static final String ACTION_DATE_ID = "action_lunch_card_date";
  static final String MODAL_TITLE = "점심카드";

  private static final ObjectMapper OM = new ObjectMapper();
  private static final String SEARCH_QUERY = "점심카드";
  private static final Pattern NAME_PATTERN = Pattern.compile("점심카드\\(([^)]+)\\)");
  private static final String[] DAY_LABELS = {"월", "화", "수", "목", "금"};
  private static final DayOfWeek[] DAY_OF_WEEKS = {
      DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
  };

  // Lambda 컨테이너 warm 재사용: S3Client ~300ms, CalendarClient ~1200ms 절약.
  private static volatile S3Client cachedS3Client;
  private static volatile GoogleCalendarClient cachedCalendarClient;
  private static volatile Map<String, String> cachedTeamMemberMap;
  private static volatile String cachedLunchCardCalendarId;

  private final SlackApiService slackApiService;
  private final WorkerMessageService workerMessageService;
  private final String configBucket;
  private final String configKey;
  private final String teamMembersKey;

  public LunchCardFacade() {
    this.slackApiService = new SlackApiService();
    this.workerMessageService = WorkerMessageService.getInstance();
    this.configBucket = System.getenv("CONFIG_BUCKET");
    String ck = System.getenv("CONFIG_KEY");
    this.configKey = (ck != null && !ck.isBlank()) ? ck : "config.json";
    String tmKey = System.getenv("TEAM_MEMBERS_KEY");
    this.teamMembersKey = (tmKey != null && !tmKey.isBlank()) ? tmKey : "team-members.json";
  }

  public LunchCardFacade(SlackApiService slackApiService) {
    this.slackApiService = slackApiService;
    this.workerMessageService = WorkerMessageService.getInstance();
    this.configBucket = System.getenv("CONFIG_BUCKET");
    String ck = System.getenv("CONFIG_KEY");
    this.configKey = (ck != null && !ck.isBlank()) ? ck : "config.json";
    String tmKey = System.getenv("TEAM_MEMBERS_KEY");
    this.teamMembersKey = (tmKey != null && !tmKey.isBlank()) ? tmKey : "team-members.json";
  }

  /**
   * /점심카드 커맨드 수신 시 Calendar 조회 후 모달을 연다.
   * Calendar 조회 실패 시에도 빈 데이터로 모달을 열어 사용자에게 안내한다.
   */
  public APIGatewayProxyResponseEvent handleCommand(
      String triggerId, String userId, String userName) {
    try {
      log.info("점심카드 커맨드: userId={}, userName={}", userId, userName);

      String today = DateTimeUtil.formatDate(DateTimeUtil.todayKst());
      String requesterName = resolveRequesterName(userId, userName);
      ViewData viewData = buildViewData(userName, userId, today, requesterName);

      slackApiService.openLunchCardModal(triggerId, viewData);
      log.info("점심카드 모달 열기 완료: userId={}", userId);
      return HttpResponse.ok("");
    } catch (Exception e) {
      log.error("점심카드 커맨드 처리 실패: userId={}", userId, e);
      return HttpResponse.ok("");
    }
  }

  /**
   * 점심카드 모달 submit 처리.
   * SQS 위임 + join() 패턴으로 Lambda freeze 전에 전송을 보장한다.
   * 전송 결과에 따라 modalResult 로 성공/실패 팝업을 반환한다.
   */
  public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
    LunchCardModalSubmit modal;
    try {
      modal = LunchCardModalSubmit.parse(body);
    } catch (Exception e) {
      log.warn("점심카드 모달 페이로드 파싱 실패: {}", e.getMessage());
      return HttpResponse.modalResult(false, "요청을 처리할 수 없습니다.", MODAL_TITLE);
    }

    if (!modal.isViewSubmission()) {
      log.info("view_submission 아님, 무시: type={}", modal.getType());
      return HttpResponse.ok("");
    }

    log.info("점심카드 submit: user={}, date={}, action={}",
        modal.getUserName(), modal.getDate(), modal.getAction());

    if (!modal.hasDate()) {
      return HttpResponse.modalError("block_lunch_card_date", "날짜를 선택해주세요.");
    }
    if (!modal.isValidAction()) {
      return HttpResponse.modalError("block_lunch_card_date", "올바른 요청이 아닙니다. 모달을 다시 열어주세요.");
    }

    AtomicBoolean success = new AtomicBoolean(false);

    Thread sqsThread = new Thread(() -> {
      try {
        String messageId = sendLunchCardToWorker(modal);
        log.info("점심카드 SQS 전송 완료: messageId={}, user={}, date={}, action={}",
            messageId, modal.getUserName(), modal.getDate(), modal.getAction());
        success.set(true);
      } catch (Exception e) {
        log.error("점심카드 SQS 전송 실패: user={}, date={}, action={}",
            modal.getUserName(), modal.getDate(), modal.getAction(), e);
      }
    }, "lunch-card-sqs-sender");
    sqsThread.start();

    try {
      sqsThread.join(2500);
      if (sqsThread.isAlive()) {
        sqsThread.interrupt();
        log.warn("점심카드 SQS 전송 타임아웃 (2500ms): user={}", modal.getUserName());
        return HttpResponse.modalResult(false,
            "처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.", MODAL_TITLE);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("점심카드 SQS 전송 스레드 인터럽트: user={}", modal.getUserName());
      return HttpResponse.modalResult(false,
          "처리가 중단되었습니다. 잠시 후 다시 시도해주세요.", MODAL_TITLE);
    }

    if (success.get()) {
      String msg = modal.isApply()
          ? "점심카드 신청이 완료되었습니다."
          : "점심카드 취소가 완료되었습니다.";
      return HttpResponse.modalResult(true, msg, MODAL_TITLE);
    } else {
      return HttpResponse.modalResult(false,
          "점심카드 처리에 실패했습니다. 잠시 후 다시 시도해주세요.", MODAL_TITLE);
    }
  }

  /**
   * 점심카드 모달의 block_actions 처리.
   * 날짜 변경(action_lunch_card_date) 시 Calendar 재조회 후 views.update 로 모달을 갱신한다.
   *
   * Calendar 조회 + views.update 를 별도 스레드에서 실행하고 join(2500) 으로
   * Lambda freeze 전에 완료를 보장한다. 3초 제한 내에 200 반환을 확보한다.
   */
  public APIGatewayProxyResponseEvent handleBlockAction(String body) {
    try {
      String decoded = URLDecoder.decode(
          body.substring("payload=".length()), StandardCharsets.UTF_8);
      JsonNode payload = OM.readTree(decoded);

      String viewId = payload.path("view").path("id").asText("");
      String userId = payload.path("user").path("id").asText("");
      String meta = payload.path("view").path("private_metadata").asText("");
      String[] metaParts = meta.split("\\|");
      String userName = metaParts.length >= 2 ? metaParts[1] : "";

      String selectedDate = extractSelectedDate(payload);

      log.info("점심카드 block_action: userId={}, date={}", userId, selectedDate);

      // Calendar 조회 + views.update 를 별도 스레드에서 실행 (3초 제한 내 완료 보장)
      Thread updateThread = new Thread(() -> {
        try {
          String requesterName = resolveRequesterName(userId, userName);
          ViewData viewData = buildViewData(userName, userId, selectedDate, requesterName);
          slackApiService.updateLunchCardView(viewId, viewData);
          log.info("점심카드 모달 갱신 완료: userId={}, date={}", userId, selectedDate);
        } catch (Exception e) {
          log.error("점심카드 모달 갱신 실패: userId={}", userId, e);
        }
      }, "lunch-card-view-update");
      updateThread.start();

      try {
        updateThread.join(2500);
        if (updateThread.isAlive()) {
          log.warn("점심카드 모달 갱신 타임아웃 (2500ms): userId={}", userId);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("점심카드 모달 갱신 스레드 인터럽트: userId={}", userId);
      }
    } catch (Exception e) {
      log.error("점심카드 block_action 페이로드 파싱 실패", e);
    }

    return HttpResponse.ok("");
  }

  /**
   * SQS 를 통해 worker 에 점심카드 처리를 위임한다.
   * 테스트에서 spy 로 오버라이드할 수 있도록 package-private 으로 분리한다.
   */
  String sendLunchCardToWorker(LunchCardModalSubmit modal) {
    return workerMessageService.sendLunchCard(
        modal.getUserId(), modal.getUserName(),
        modal.getDate(), modal.getAction());
  }

  // ── Calendar 조회 + ViewData 구성 ──

  /**
   * Calendar 이벤트를 조회하여 ViewData 를 구성한다.
   * Calendar 조회 실패 시 빈 데이터로 구성한다.
   */
  ViewData buildViewData(String userName, String userId,
                         String selectedDate, String requesterName) {
    LocalDate date = LocalDate.parse(selectedDate);
    List<Event> weekEvents = List.of();
    List<Event> monthEvents = List.of();

    try {
      GoogleCalendarClient calendarClient = getOrCreateCalendarClient();
      String calendarId = loadLunchCardCalendarId();
      if (calendarId != null && !calendarId.isBlank()) {
        weekEvents = queryWeekEvents(calendarClient, date);
        monthEvents = queryMonthEvents(calendarClient, date);
      } else {
        log.warn("lunchCard.calendar_id 미설정 — 빈 데이터로 모달 표시");
      }
    } catch (Exception e) {
      log.error("점심카드 Calendar 조회 실패 — 빈 데이터로 모달 표시: {}", e.getMessage());
    }

    int weeklyCount = countEvents(weekEvents);
    int monthlyCount = countEvents(monthEvents);

    log.info("점심카드 조회 결과: selectedDate={}, weekEvents={}, monthEvents={}",
        selectedDate, weeklyCount, monthlyCount);
    for (Event event : weekEvents) {
      log.info("점심카드 이벤트: summary={}, date={}, dateTime={}, extractedDate={}",
          event.getSummary(),
          event.getStart() != null ? event.getStart().getDate() : null,
          event.getStart() != null ? event.getStart().getDateTime() : null,
          extractEventDate(event));
    }

    List<Event> dayEvents = filterEventsByDate(weekEvents, date);

    Status status = determineStatus(dayEvents, requesterName);
    String registeredUserName = (status == Status.OTHER_REGISTERED)
        ? findRegisteredUserName(dayEvents) : null;

    Map<String, List<String>> dayOfWeekMap = buildDayOfWeekMap(weekEvents, date);

    return new ViewData(
        userName, userId, selectedDate,
        status, registeredUserName,
        weeklyCount, monthlyCount, dayOfWeekMap);
  }

  // ── Calendar 쿼리 ──

  private List<Event> queryWeekEvents(GoogleCalendarClient client, LocalDate date) {
    LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate weekEnd = weekStart.plusDays(5); // 금요일 다음날
    String timeMin = weekStart + "T00:00:00+09:00";
    String timeMax = weekEnd + "T00:00:00+09:00";
    return filterByLunchCardSummary(
        client.listEvents(loadLunchCardCalendarId(), timeMin, timeMax, null));
  }

  private List<Event> queryMonthEvents(GoogleCalendarClient client, LocalDate date) {
    LocalDate monthStart = date.withDayOfMonth(1);
    LocalDate monthEnd = date.with(TemporalAdjusters.lastDayOfMonth()).plusDays(1);
    String timeMin = monthStart + "T00:00:00+09:00";
    String timeMax = monthEnd + "T00:00:00+09:00";
    return filterByLunchCardSummary(
        client.listEvents(loadLunchCardCalendarId(), timeMin, timeMax, null));
  }

  /**
   * summary가 "점심카드"로 시작하는 이벤트만 필터링한다.
   * Google Calendar API searchQuery(setQ)는 결과 누락 가능 → 전체 fetch 후 Java에서 필터링.
   */
  static List<Event> filterByLunchCardSummary(List<Event> events) {
    List<Event> result = new ArrayList<>();
    for (Event event : events) {
      String summary = event.getSummary();
      if (summary != null && summary.startsWith(SEARCH_QUERY)) {
        result.add(event);
      }
    }
    return result;
  }

  // ── 카운트 헬퍼 ──

  static int countEvents(List<Event> events) {
    return events.size();
  }

  static List<Event> filterEventsByDate(List<Event> events, LocalDate date) {
    String dateStr = date.toString();
    List<Event> result = new ArrayList<>();
    for (Event event : events) {
      String eventDate = extractEventDate(event);
      if (dateStr.equals(eventDate)) {
        result.add(event);
      }
    }
    return result;
  }

  static Map<String, List<String>> buildDayOfWeekMap(List<Event> weekEvents, LocalDate refDate) {
    LocalDate weekStart = refDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    Map<String, List<String>> map = new LinkedHashMap<>();

    for (int i = 0; i < DAY_LABELS.length; i++) {
      LocalDate dayDate = weekStart.plusDays(i);
      List<String> users = new ArrayList<>();
      for (Event event : weekEvents) {
        String eventDate = extractEventDate(event);
        if (dayDate.toString().equals(eventDate)) {
          String name = extractNameFromSummary(event.getSummary());
          if (name != null) {
            users.add(name);
          }
        }
      }
      map.put(DAY_LABELS[i], users);
    }
    return map;
  }

  // ── 상태 판별 ──

  static Status determineStatus(List<Event> dayEvents, String requesterName) {
    if (dayEvents.isEmpty()) {
      return Status.UNREGISTERED;
    }
    for (Event event : dayEvents) {
      String name = extractNameFromSummary(event.getSummary());
      if (requesterName != null && requesterName.equals(name)) {
        return Status.SELF_REGISTERED;
      }
    }
    return Status.OTHER_REGISTERED;
  }

  private static String findRegisteredUserName(List<Event> dayEvents) {
    for (Event event : dayEvents) {
      String name = extractNameFromSummary(event.getSummary());
      if (name != null) return name;
    }
    return null;
  }

  // ── 유틸리티 ──

  static String extractNameFromSummary(String summary) {
    if (summary == null) return null;
    Matcher m = NAME_PATTERN.matcher(summary);
    return m.find() ? m.group(1) : null;
  }

  static String extractEventDate(Event event) {
    if (event.getStart() == null) return "";
    if (event.getStart().getDate() != null) {
      return event.getStart().getDate().toStringRfc3339().substring(0, 10);
    }
    if (event.getStart().getDateTime() != null) {
      // dateTime 이벤트는 타임존 포함 → KST 기준 날짜로 변환
      long millis = event.getStart().getDateTime().getValue();
      return Instant.ofEpochMilli(millis)
          .atZone(ZoneId.of("Asia/Seoul"))
          .toLocalDate()
          .toString();
    }
    return "";
  }

  private String extractSelectedDate(JsonNode payload) {
    JsonNode actions = payload.path("actions");
    for (JsonNode action : actions) {
      if ("action_lunch_card_date".equals(action.path("action_id").asText())) {
        String date = action.path("selected_date").asText("");
        if (!date.isEmpty()) return date;
      }
    }
    // datepicker 변경이 아닌 경우 현재 view state 에서 추출
    return payload.path("view").path("state").path("values")
        .path("block_lunch_card_date").path("action_lunch_card_date")
        .path("selected_date").asText(DateTimeUtil.formatDate(DateTimeUtil.todayKst()));
  }

  /**
   * 요청자의 한글 이름을 resolve 한다.
   * team-members.json 에서 slackUserId → name 매핑을 조회한다.
   * 매핑 실패 시 userName 을 폴백으로 반환한다.
   */
  private String resolveRequesterName(String userId, String userName) {
    Map<String, String> memberMap = loadTeamMemberMap();
    if (memberMap != null) {
      String name = memberMap.get(userId);
      if (name != null && !name.isBlank()) return name;
    }
    return userName;
  }

  // ── static volatile 캐싱 (CurrentTicketFacade 동일 패턴) ──

  private static S3Client getOrCreateS3Client() {
    S3Client s3 = cachedS3Client;
    if (s3 == null) {
      s3 = S3Client.builder().build();
      cachedS3Client = s3;
      log.info("[LunchCardFacade] S3Client 생성 완료 (캐시 저장)");
    }
    return s3;
  }

  private static GoogleCalendarClient getOrCreateCalendarClient() throws Exception {
    GoogleCalendarClient client = cachedCalendarClient;
    if (client != null) return client;

    String bucket = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_BUCKET");
    String key = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_KEY");
    if (key == null || key.isBlank()) key = "google-credentials.json";

    if (bucket == null || bucket.isBlank()) {
      throw new IllegalStateException("GOOGLE_CALENDAR_CREDENTIALS_BUCKET 환경변수 미설정");
    }

    byte[] credBytes = getOrCreateS3Client().getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build()
    ).readAllBytes();

    client = new GoogleCalendarClient(credBytes);
    cachedCalendarClient = client;
    log.info("[LunchCardFacade] GoogleCalendarClient 초기화 완료 (캐시 저장)");
    return client;
  }

  /**
   * S3 config.json에서 lunchCard.calendar_id를 로드한다. static volatile 캐싱을 적용한다.
   */
  private String loadLunchCardCalendarId() {
    String existing = cachedLunchCardCalendarId;
    if (existing != null) return existing;

    if (configBucket == null || configBucket.isBlank()) {
      log.warn("[LunchCardFacade] CONFIG_BUCKET 미설정 — config.json 조회 불가");
      return null;
    }
    try {
      byte[] bytes = getOrCreateS3Client().getObject(
          GetObjectRequest.builder().bucket(configBucket).key(configKey).build()
      ).readAllBytes();

      JsonNode root = OM.readTree(new String(bytes, StandardCharsets.UTF_8));
      String calendarId = root.path("lunchCard").path("calendar_id").asText("");
      if (calendarId.isEmpty()) {
        log.warn("[LunchCardFacade] config.json에 lunchCard.calendar_id 미설정");
        return null;
      }
      cachedLunchCardCalendarId = calendarId;
      log.info("[LunchCardFacade] config.json lunchCard.calendar_id 로드 완료: {}", calendarId);
      return calendarId;
    } catch (Exception e) {
      log.error("[LunchCardFacade] config.json 로드 실패", e);
      return null;
    }
  }

  private Map<String, String> loadTeamMemberMap() {
    Map<String, String> existing = cachedTeamMemberMap;
    if (existing != null) return existing;

    if (configBucket == null || configBucket.isBlank()) {
      log.warn("[LunchCardFacade] CONFIG_BUCKET 미설정 — team-members.json 조회 불가");
      return null;
    }
    try {
      byte[] bytes = getOrCreateS3Client().getObject(
          GetObjectRequest.builder().bucket(configBucket).key(teamMembersKey).build()
      ).readAllBytes();

      JsonNode root = OM.readTree(new String(bytes, StandardCharsets.UTF_8));
      JsonNode members = root.path("members");
      if (members.isMissingNode() || !members.isArray()) {
        log.warn("[LunchCardFacade] team-members.json에 'members' 배열 없음");
        return null;
      }

      Map<String, String> map = new HashMap<>();
      for (JsonNode m : members) {
        String sid = m.path("slack_user_id").asText("");
        String name = m.path("name").asText("").trim();
        if (!sid.isEmpty() && !name.isEmpty()) {
          map.put(sid, name);
        }
      }
      cachedTeamMemberMap = map;
      log.info("[LunchCardFacade] team-members.json 로드 완료: {}명", map.size());
      return map;
    } catch (Exception e) {
      log.error("[LunchCardFacade] team-members.json 로드 실패", e);
      return null;
    }
  }
}
