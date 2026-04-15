package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.calendar.model.Event;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.code.DueDateUrgencyCode;
import com.riman.automation.common.code.JiraPriorityCode;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.exception.AutomationException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.ingest.dto.slack.CurrentTicketModalSubmit;
import com.riman.automation.ingest.payload.CurrentTicketModalBuilder;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * /현재티켓 커맨드 처리 Facade.
 *
 * 두 진입점으로 구성된다.
 * 1. handleCommand()     — /현재티켓 슬래시커맨드 수신 시 기간 선택 모달을 연다.
 * 2. handleModalSubmit() — current_ticket_submit 제출 시 티켓을 조회하고 DM 을 전송한다.
 *
 * 설계 원칙: 모달 오픈과 Calendar 초기화를 분리한다.
 * 모달을 여는 데는 SlackClient 만 필요하므로 항상 가능하며, GoogleCalendarClient 는
 * 실제 티켓 조회 시점에 lazy 초기화된다. 이로써 GOOGLE_CALENDAR_CREDENTIALS_BUCKET
 * 환경변수가 없어도 모달은 정상 표시되고 조회 단계에서 오류 DM 으로 안내할 수 있다.
 *
 * 조회 기준:
 * 담당자는 요청자 본인(team-members.json 의 name 과 캘린더 이벤트 제목의 "(이름)" 매칭),
 * 상태는 DONE 이 아닌 미완료, 기간은 현재 분기 전체를 수집 후 기간 드롭다운
 * (daily/weekly/monthly/quarterly)으로 앱 레벨에서 필터링한다.
 *
 * 필수 환경변수: SLACK_BOT_TOKEN(모달/DM), TICKET_CALENDAR_ID(조회),
 * JIRA_BASE_URL(링크 생성), GOOGLE_CALENDAR_CREDENTIALS_BUCKET/KEY(조회).
 */
@Slf4j
public class CurrentTicketFacade {

  static final String SLASH_COMMAND = "/현재티켓";
  static final String CALLBACK_ID = "current_ticket_submit";

  // 이슈 키 파싱 패턴 (DailyCalendarTicketCollector 와 동일).
  private static final Pattern ISSUE_KEY_PATTERN =
      Pattern.compile("\\[Jira\\]\\s+([A-Z]+-\\d+)|\\[([A-Z]+-\\d+)\\]");

  // 담당자 이름 파싱 패턴 (DailyCalendarTicketCollector 와 동일).
  private static final Pattern ASSIGNEE_PATTERN =
      Pattern.compile("\\(([^)]+)\\)\\s*$");

  private static final ObjectMapper OM = new ObjectMapper();

  private static final String INDENT = "\u3000\u3000";
  private static final String INDENT2 = "\u3000\u3000\u3000";

  // Lambda 컨테이너 warm 재사용을 위해 무거운 리소스를 static 캐시한다.
  // S3Client(~300ms×2), Calendar 인증 키 로드(~600ms), 팀원 정보(~100ms) 절약.
  private static volatile S3Client cachedS3Client;
  private static volatile GoogleCalendarClient cachedCalendarClient;
  private static volatile Map<String, String> cachedTeamMemberMap;

  private final SlackClient slackClient;
  private final String jiraBaseUrl;
  private final String ticketCalendarId;
  private final String configBucket;
  private final String teamMembersKey;

  /**
   * SlackFacade 생성자에서 주입되는 생성자.
   * GoogleCalendarClient 는 여기서 받지 않고 조회 시점에 lazy 초기화한다.
   * 담당자 매칭은 team-members.json 의 slackUserId → name 으로 처리한다.
   */
  public CurrentTicketFacade(SlackClient slackClient) {
    this.slackClient = slackClient;
    this.jiraBaseUrl = System.getenv("JIRA_BASE_URL") != null
        ? System.getenv("JIRA_BASE_URL") : "https://riman-it.atlassian.net";
    this.ticketCalendarId = System.getenv("TICKET_CALENDAR_ID");
    this.configBucket = System.getenv("CONFIG_BUCKET");
    String tmKey = System.getenv("TEAM_MEMBERS_KEY");
    this.teamMembersKey = (tmKey != null && !tmKey.isBlank()) ? tmKey : "team-members.json";
  }

  /**
   * /현재티켓 커맨드 수신 처리. 기간 선택 모달을 연 뒤 200 을 반환한다.
   *
   * 사용자가 모달에서 기간을 선택하는 1~3초 동안 데몬 스레드로 CalendarClient 를
   * 미리 초기화(pre-warm)한다. handleCommand() 리턴 후 Lambda 가 freeze 되면
   * 스레드는 일시중지되고 다음 invocation(modal submit)에서 thaw 되며 초기화가 완료된다.
   * 이로써 modal submit 시점에는 Calendar API + DM 전송만 수행되어 ~2.5초에 수렴한다.
   */
  public APIGatewayProxyResponseEvent handleCommand(
      String triggerId, String userId, String userName) {
    try {
      log.info("현재티켓 커맨드: userId={}, userName={}", userId, userName);
      String payload = CurrentTicketModalBuilder.build(triggerId, userId);
      slackClient.openView(payload);
      log.info("현재티켓 모달 열기 완료: userId={}", userId);

      if (cachedCalendarClient == null) {
        Thread preWarm = new Thread(() -> {
          try {
            getOrCreateCalendarClient();
          } catch (Exception e) {
            log.debug("현재티켓 캘린더 사전 초기화 실패 (submit 시점에 재시도): {}", e.getMessage());
          }
        }, "current-ticket-prewarm");
        preWarm.setDaemon(true);
        preWarm.start();
      }

      return HttpResponse.ok("");
    } catch (AutomationException e) {
      log.error("현재티켓 커맨드 처리 실패 [{}]: userId={}, cause={}",
          e.getErrorCode(), userId, e.getMessage());
      return HttpResponse.internalError();
    } catch (Exception e) {
      log.error("현재티켓 커맨드 처리 중 예기치 않은 오류: userId={}", userId, e);
      return HttpResponse.internalError();
    }
  }

  /**
   * 기간 선택 모달 제출 처리 (current_ticket_submit view_submission).
   *
   * Lambda Runtime 은 handleRequest() 가 return 된 후에만 HTTP 응답을 전송하므로
   * Thread.join() 은 Slack 응답 지연으로 직결된다. 이에 static 캐시 + 사전 초기화 +
   * join(timeout) 조합으로 대응한다.
   * static 캐시로 ~1,200ms 절약, handleCommand() 단계의 pre-warm 으로 사용자 상호작용 시간을 활용,
   * join(2500) 으로 최대 2.5초만 대기하여 Slack 3초 제한을 준수한다.
   * 타임아웃 발생 시에도 worker 스레드는 다음 Lambda invocation 에서 재개되어 DM 이 발송된다.
   */
  public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
    CurrentTicketModalSubmit modal;
    try {
      modal = CurrentTicketModalSubmit.parse(body);
    } catch (Exception e) {
      log.warn("현재티켓 모달 파싱 실패: {}", e.getMessage());
      return HttpResponse.badRequest("Invalid payload");
    }

    if (!modal.isViewSubmission()) {
      return HttpResponse.ok("");
    }

    final String userId = modal.getUserId();
    final String period = modal.getPeriod();
    log.info("현재티켓 조회 요청 수신: userId={}, period={}", userId, period);

    Thread worker = new Thread(() -> {
      try {
        log.info("현재티켓 DM 전송 시작: userId={}, period={}", userId, period);
        sendTicketDm(userId, period);
      } catch (Exception e) {
        log.error("현재티켓 DM 전송 실패: userId={}, period={}", userId, period, e);
      }
    }, "current-ticket-worker");
    worker.start();

    try {
      worker.join(2500);
      if (worker.isAlive()) {
        log.warn("현재티켓 worker 2.5초 초과 — Slack 응답 우선 반환: userId={}", userId);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("현재티켓 worker Thread 인터럽트: userId={}", userId);
    }

    // view_submission 에 대한 정상 응답: response_action=clear 로 모달을 닫는다.
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withHeaders(java.util.Map.of("Content-Type", "application/json"))
        .withBody("{\"response_action\":\"clear\"}");
  }

  /**
   * 기간에 맞는 캘린더 범위로 티켓을 수집한 뒤 DM 으로 전송한다.
   *
   * 조회 범위는 항상 현재 분기 전체이며, daily/weekly/monthly/quarterly 필터는 앱 레벨에서 적용한다.
   * 공통 필터는 담당자 == 요청자 본인, 상태 != DONE 이다.
   */
  private void sendTicketDm(String userId, String period) {
    if (ticketCalendarId == null || ticketCalendarId.isBlank()) {
      log.warn("TICKET_CALENDAR_ID 환경변수 미설정 — 현재티켓 조회 불가: userId={}", userId);
      sendErrorDm(userId, "⚠️ 티켓 캘린더 ID가 설정되지 않아 조회할 수 없습니다.\n관리자에게 문의해 주세요.");
      return;
    }

    GoogleCalendarClient calendarClient;
    try {
      calendarClient = getOrCreateCalendarClient();
    } catch (Exception e) {
      log.error("GoogleCalendarClient 초기화 실패: userId={}, cause={}", userId, e.getMessage());
      sendErrorDm(userId, "⚠️ 캘린더 연결에 실패했습니다. 관리자에게 문의해 주세요.");
      return;
    }

    // 캘린더 이벤트 제목의 "(이름)" 과 매칭할 요청자 이름은 team-members.json 에서 조회한다.
    String requesterName = resolveAssigneeName(userId);
    if (requesterName == null || requesterName.isBlank()) {
      log.warn("팀원 이름 조회 실패: userId={} — team-members.json에 등록된 팀원인지 확인 필요", userId);
      sendErrorDm(userId, "⚠️ 팀원 정보를 조회할 수 없어 티켓을 검색하지 못했습니다.\n"
          + "team-members.json에 본인의 Slack User ID가 등록되어 있어야 합니다.");
      return;
    }

    // 현재 분기 범위 계산.
    LocalDate today = DateTimeUtil.todayKst();
    int quarter = (today.getMonthValue() - 1) / 3 + 1;
    LocalDate quarterStart = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
    LocalDate quarterEnd = LocalDate.of(today.getYear(), quarter * 3, 1)
        .with(TemporalAdjusters.lastDayOfMonth());

    // 캘린더 이벤트의 start.date 는 duedate 와 일치하므로 범위를 좁히면 누락이 발생한다.
    // 분기 전체를 조회한 뒤 period 조건으로 필터링한다.
    log.info("현재티켓 캘린더 조회 범위: userId={}, name={}, period={}, 분기={} ~ {}",
        userId, requesterName, period, quarterStart, quarterEnd);

    List<TicketItem> allTickets = collectTickets(calendarClient, requesterName,
        quarterStart, quarterEnd);

    // 기간별 필터 규칙.
    // daily    : dueDate == null || dueDate <= 오늘
    // weekly   : dueDate == null || dueDate <= 이번주 일요일
    // monthly  : dueDate == null || dueDate <= 이번달 말일
    // quarterly: 필터 없음 (분기 전체 미완료)
    List<TicketItem> tickets;
    switch (period) {
      case "daily":
        tickets = allTickets.stream()
            .filter(t -> t.dueDate == null || !t.dueDate.isAfter(today))
            .collect(Collectors.toList());
        break;
      case "weekly":
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate thisSunday = thisMonday.plusDays(6);
        tickets = allTickets.stream()
            .filter(t -> t.dueDate == null || !t.dueDate.isAfter(thisSunday))
            .collect(Collectors.toList());
        break;
      case "monthly":
        LocalDate endOfMonth = today.with(TemporalAdjusters.lastDayOfMonth());
        tickets = allTickets.stream()
            .filter(t -> t.dueDate == null || !t.dueDate.isAfter(endOfMonth))
            .collect(Collectors.toList());
        break;
      case "quarterly":
      default:
        tickets = allTickets;
        break;
    }

    log.info("현재티켓 필터링 완료: userId={}, period={}, 전체={}건 → 필터후={}건",
        userId, period, allTickets.size(), tickets.size());

    // DM 채널을 열고 Block Kit 메시지를 전송한다.
    String dmChannelId = slackClient.openDm(userId);
    String message = buildMessageWithChannel(
        tickets, requesterName, period, today, quarter,
        quarterStart, quarterEnd, dmChannelId);
    slackClient.postMessage(message);

    log.info("현재티켓 DM 전송 완료: userId={}, name={}, 티켓={}건",
        userId, requesterName, tickets.size());
  }

  /**
   * S3Client 를 캐시에서 반환하거나 없으면 생성 후 캐시한다.
   * Lambda 컨테이너 수명 동안 1회만 생성되어 S3Client.builder().build() 의 ~300ms 비용을 절약한다.
   */
  private static S3Client getOrCreateS3Client() {
    S3Client s3 = cachedS3Client;
    if (s3 == null) {
      s3 = S3Client.builder().build();
      cachedS3Client = s3;
      log.info("[CurrentTicketFacade] S3Client 생성 완료 (캐시 저장)");
    }
    return s3;
  }

  /**
   * GoogleCalendarClient 를 캐시에서 반환하거나 S3 에서 인증 키를 로드하여 생성 후 캐시한다.
   * handleCommand() 의 pre-warm 스레드에서 미리 호출되므로 modal submit 시점에는 캐시 히트된다.
   * GOOGLE_CALENDAR_CREDENTIALS_BUCKET 미설정이거나 S3 로드 실패 시 예외를 throw 한다.
   */
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
    log.info("[CurrentTicketFacade] GoogleCalendarClient 초기화 완료 (캐시 저장)");
    return client;
  }

  /**
   * Slack User ID 로 team-members.json 의 name(한글 이름)을 조회한다.
   * 첫 호출 시 전체 멤버 맵을 static 캐시하여 이후 O(1) 조회로 동작한다.
   * 캘린더 이벤트 제목 "[Jira] CCE-123 (조주현)" 의 "(조주현)" 과 name 이 동일하므로 정확한 매칭이 가능하다.
   */
  private String resolveAssigneeName(String slackUserId) {
    Map<String, String> memberMap = cachedTeamMemberMap;
    if (memberMap == null) {
      memberMap = loadTeamMemberMap(configBucket, teamMembersKey);
    }
    if (memberMap == null) return null;

    String name = memberMap.get(slackUserId);
    if (name != null) {
      log.info("[CurrentTicketFacade] 팀원 이름 조회 완료: userId={}, name={}", slackUserId, name);
    } else {
      log.warn("[CurrentTicketFacade] slackUserId={}에 해당하는 팀원 없음", slackUserId);
    }
    return name;
  }

  /**
   * team-members.json 을 S3 에서 로드하여 slackUserId → name 맵으로 캐시한다.
   * CONFIG_BUCKET 미설정이거나 로드/파싱 실패 시 null 을 반환한다.
   */
  private static Map<String, String> loadTeamMemberMap(String bucket, String key) {
    Map<String, String> existing = cachedTeamMemberMap;
    if (existing != null) return existing;

    if (bucket == null || bucket.isBlank()) {
      log.warn("[CurrentTicketFacade] CONFIG_BUCKET 미설정 — team-members.json 조회 불가");
      return null;
    }
    try {
      byte[] bytes = getOrCreateS3Client().getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).build()
      ).readAllBytes();

      JsonNode root = OM.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      JsonNode members = root.path("members");
      if (members.isMissingNode() || !members.isArray()) {
        log.warn("[CurrentTicketFacade] team-members.json에 'members' 배열 없음");
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
      log.info("[CurrentTicketFacade] team-members.json 캐시 완료: {}명", map.size());
      return map;
    } catch (Exception e) {
      log.error("[CurrentTicketFacade] team-members.json 로드 실패: {}", e.getMessage());
      return null;
    }
  }

  /**
   * 지정 기간의 캘린더 이벤트에서 요청자 담당 미완료 티켓만 추출한다.
   * 파싱 로직은 DailyCalendarTicketCollector.parseTicketEvent() 와 동일하다.
   * 결과는 날짜 오름차순 → 우선순위 오름차순으로 정렬된다.
   */
  private List<TicketItem> collectTickets(
      GoogleCalendarClient calendarClient,
      String requesterName,
      LocalDate from,
      LocalDate to) {

    String timeMin = from + "T00:00:00+09:00";
    String timeMax = to + "T23:59:59+09:00";

    List<Event> events;
    try {
      // searchQuery=null 로 캘린더 전체를 조회한 뒤 Java 에서 이름 매칭한다.
      // requesterName 을 searchQuery 로 넘기면 Google API full-text 검색이 되어
      // 이름이 제목에 있어도 누락되거나 오매칭이 발생할 수 있다.
      events = calendarClient.listEvents(ticketCalendarId, timeMin, timeMax, null);
    } catch (Exception e) {
      log.error("현재티켓 캘린더 조회 실패: from={}, to={}", from, to, e);
      return List.of();
    }

    log.info("현재티켓 캘린더 이벤트 수신: 전체={}건, 담당자 필터={}", events.size(), requesterName);

    List<TicketItem> tickets = new ArrayList<>();
    LocalDate today = DateTimeUtil.todayKst();

    for (Event event : events) {
      try {
        TicketItem item = parseTicketEvent(event, requesterName, today);
        if (item == null) continue;
        if (item.status == JiraStatusCode.DONE) continue;
        tickets.add(item);
      } catch (Exception e) {
        log.warn("현재티켓 이벤트 파싱 실패: title={}", event.getSummary(), e);
      }
    }

    tickets.sort(Comparator
        .comparing((TicketItem t) -> t.dueDate == null ? LocalDate.MAX : t.dueDate)
        .thenComparingInt(t -> t.priority.getOrder()));

    log.info("현재티켓 수집 완료: name={}, 미완료={}건", requesterName, tickets.size());
    return tickets;
  }

  /**
   * 캘린더 이벤트를 TicketItem 으로 변환한다.
   * 이슈 키가 없거나 담당자가 일치하지 않으면 null 을 반환한다.
   */
  private TicketItem parseTicketEvent(Event event, String requesterName, LocalDate today) {
    String title = event.getSummary();
    if (title == null) return null;

    Matcher keyMatcher = ISSUE_KEY_PATTERN.matcher(title);
    if (!keyMatcher.find()) return null;
    String issueKey = keyMatcher.group(1) != null ? keyMatcher.group(1) : keyMatcher.group(2);

    List<String> assigneeNames = parseAssigneeNames(title);
    // assignees 가 비어있으면 공용 티켓으로 취급하여 통과시키고, 있으면 정확히 일치하는 경우만 통과시킨다.
    boolean isAssigned = assigneeNames.isEmpty()
        || assigneeNames.stream().anyMatch(n -> n.trim().equals(requesterName.trim()));
    if (!isAssigned) {
      log.debug("담당자 불일치: title={}, assignees={}, requester={}",
          title, assigneeNames, requesterName);
      return null;
    }

    LocalDate dueDate = dateOf(event);
    JiraPriorityCode priority = detectPriority(event.getDescription());
    JiraStatusCode status = detectStatus(event.getDescription());
    DueDateUrgencyCode urgency = DueDateUrgencyCode.of(today, dueDate);

    String summary = extractTitleFromDescription(event.getDescription());
    if (summary == null || summary.isBlank()) summary = extractSummaryFromTitle(title);
    if (summary == null || summary.isBlank()) summary = issueKey;

    return new TicketItem(issueKey, summary, status, priority, dueDate, urgency,
        jiraBaseUrl + "/browse/" + issueKey);
  }

  private List<String> parseAssigneeNames(String title) {
    if (title == null) return List.of();
    Matcher m = ASSIGNEE_PATTERN.matcher(title);
    if (!m.find()) return List.of();
    List<String> names = new ArrayList<>();
    for (String part : m.group(1).split(",")) {
      String n = part.trim();
      if (!n.isEmpty()) names.add(n);
    }
    return names;
  }

  private LocalDate dateOf(Event event) {
    try {
      if (event.getStart() == null) return null;
      if (event.getStart().getDate() != null)
        return LocalDate.parse(event.getStart().getDate().toString());
      if (event.getStart().getDateTime() != null) {
        String dt = event.getStart().getDateTime().toString();
        return LocalDate.parse(dt.length() >= 10 ? dt.substring(0, 10) : dt);
      }
    } catch (Exception e) {
      log.debug("날짜 파싱 실패: eventId={}", event.getId());
    }
    return null;
  }

  /**
   * description 의 "Priority: ..." 라인에서 우선순위를 파싱한다.
   * 과거에는 title 의 이모지로 감지했으나 실제 이벤트 제목은 "[Jira] CCE-123 (이름)" 포맷에
   * 이모지가 없어 항상 MEDIUM 을 반환하는 버그가 있었다. description 기반으로 수정되어 있다.
   */
  private JiraPriorityCode detectPriority(String description) {
    if (description == null || description.isBlank()) return JiraPriorityCode.MEDIUM;
    for (String line : description.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Priority: ")) {
        String p = trimmed.substring("Priority: ".length()).trim().toLowerCase();
        switch (p) {
          case "highest":
            return JiraPriorityCode.HIGHEST;
          case "high":
            return JiraPriorityCode.HIGH;
          case "medium":
            return JiraPriorityCode.MEDIUM;
          case "low":
            return JiraPriorityCode.LOW;
          case "lowest":
            return JiraPriorityCode.LOWEST;
          default:
            return JiraPriorityCode.MEDIUM;
        }
      }
    }
    return JiraPriorityCode.MEDIUM;
  }

  private JiraStatusCode detectStatus(String description) {
    if (description == null || description.isBlank()) return JiraStatusCode.IN_PROGRESS;
    for (String line : description.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Status: ")) {
        return JiraStatusCode.fromStatusName(trimmed.substring("Status: ".length()).trim());
      }
    }
    return JiraStatusCode.IN_PROGRESS;
  }

  private String extractTitleFromDescription(String description) {
    if (description == null || description.isBlank()) return null;
    for (String line : description.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Title: ")) {
        String t = trimmed.substring("Title: ".length()).trim();
        return t.isBlank() ? null : t;
      }
    }
    return null;
  }

  private String extractSummaryFromTitle(String title) {
    if (title == null) return null;
    String cleaned = ISSUE_KEY_PATTERN.matcher(title).replaceAll("").trim();
    cleaned = ASSIGNEE_PATTERN.matcher(cleaned).replaceAll("").trim();
    cleaned = cleaned.replaceAll("^\\s*[-_|]\\s*", "").trim();
    return cleaned.isBlank() ? null : cleaned;
  }

  /**
   * 티켓 조회 결과를 Slack Block Kit 메시지 JSON 으로 구성한다.
   * 일일 보고서의 티켓 현황 포맷과 동일하게 날짜별 그룹핑 + 우선순위 뱃지 + 당일/초과 강조를 적용한다.
   */
  private String buildMessageWithChannel(
      List<TicketItem> tickets,
      String requesterName,
      String period,
      LocalDate today,
      int quarter,
      LocalDate quarterStart,
      LocalDate quarterEnd,
      String channelId) {

    String periodTitle = buildPeriodTitle(period);
    String periodDetail = buildPeriodDetail(period, today, quarter, quarterStart, quarterEnd);

    SlackBlockBuilder builder = SlackBlockBuilder
        .forChannel(channelId)
        .fallbackText(periodTitle + " | " + requesterName)
        .noUnfurl();

    builder.header(periodTitle + "  |  " + requesterName);

    builder.context(periodDetail
        + "   |   *" + tickets.size() + "*건"
        + "   |   " + DateTimeUtil.formatDateTime(DateTimeUtil.nowKst()) + " KST");

    builder.divider();

    if (tickets.isEmpty()) {
      builder.section(INDENT + "_조회된 티켓이 없습니다._");
    } else {
      appendTicketSection(builder, tickets, today);
    }

    return builder.build();
  }

  /**
   * 티켓 목록을 날짜별로 그룹핑하여 Slack section 블록으로 추가한다.
   * section 하나의 본문 길이 제한(2800자)을 넘으면 다음 section 으로 분리한다.
   */
  private void appendTicketSection(
      SlackBlockBuilder builder,
      List<TicketItem> tickets,
      LocalDate today) {

    LinkedHashMap<LocalDate, List<TicketItem>> groups = new LinkedHashMap<>();
    for (TicketItem t : tickets) {
      groups.computeIfAbsent(t.dueDate, k -> new ArrayList<>()).add(t);
    }

    final int SECTION_LIMIT = 2800;
    StringBuilder sb = new StringBuilder();

    for (Map.Entry<LocalDate, List<TicketItem>> entry : groups.entrySet()) {
      LocalDate due = entry.getKey();
      String dateHdr = (due == null) ? "*기한없음*" : "*" + formatDayLabel(due) + "*";
      String hdrLine = INDENT + dateHdr + "\n";
      if (sb.length() + hdrLine.length() > SECTION_LIMIT) {
        builder.section(sb.toString().trim());
        sb = new StringBuilder();
      }
      sb.append(hdrLine);

      for (TicketItem t : entry.getValue()) {
        String line = formatTicketLine(t, today) + "\n";
        if (sb.length() + line.length() > SECTION_LIMIT) {
          builder.section(sb.toString().trim());
          sb = new StringBuilder();
        }
        sb.append(line);
      }
    }

    if (sb.length() > 0) {
      builder.section(sb.toString().trim());
    }
  }

  /**
   * 티켓 한 줄을 포맷한다.
   * 당일 마감은 bold, 기한 초과 미완료는 백틱 강조, 그 외는 plain 으로 표기하며 우선순위 뱃지를 덧붙인다.
   */
  private String formatTicketLine(TicketItem t, LocalDate today) {
    String badge = buildPriorityBadge(t.priority);
    String link = "<" + t.url + "|[" + t.issueKey + "]>";
    boolean isOverdue = t.dueDate != null && t.dueDate.isBefore(today);
    boolean isDueToday = t.dueDate != null && t.dueDate.isEqual(today);

    if (isDueToday) {
      return INDENT2 + "• *" + link + " " + t.summary + "*" + badge;
    } else if (isOverdue) {
      String issueLink = "<" + t.url + "|`[" + t.issueKey + "]`>";
      return INDENT2 + "• " + issueLink + " `" + t.summary + "`" + badge;
    } else {
      return INDENT2 + "• " + link + " " + t.summary + badge;
    }
  }

  /**
   * 우선순위 뱃지를 반환한다. Highest/High 만 표시하며 Medium 이하는 노이즈 제거를 위해 공란이다.
   */
  private String buildPriorityBadge(JiraPriorityCode priority) {
    if (priority == null) return "";
    switch (priority) {
      case HIGHEST:
        return "  (🔺Highest)";
      case HIGH:
        return "  (🔸High)";
      default:
        return "";
    }
  }

  private String formatDayLabel(LocalDate date) {
    return String.format("%02d/%02d(%s)",
        date.getMonthValue(),
        date.getDayOfMonth(),
        DateTimeUtil.DISPLAY_FMT.format(date).replaceAll(".*\\((.*)\\).*", "$1"));
  }

  /**
   * 헤더용 기간 제목을 반환한다.
   */
  private String buildPeriodTitle(String period) {
    switch (period) {
      case "daily":
        return "📅 일별 미완료 티켓 조회";
      case "weekly":
        return "📆 주별 미완료 티켓 조회";
      case "monthly":
        return "📋 월별 미완료 티켓 조회";
      case "quarterly":
      default:
        return "🗓️ 분기별 미완료 티켓 조회";
    }
  }

  /**
   * 컨텍스트용 기간 상세(조회 범위 표시)를 반환한다.
   */
  private String buildPeriodDetail(
      String period, LocalDate today, int quarter,
      LocalDate quarterStart, LocalDate quarterEnd) {
    switch (period) {
      case "daily":
        return "기준일: *" + DateTimeUtil.formatDate(today) + "* 이하 마감";
      case "weekly":
        LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sun = mon.plusDays(6);
        return "기준주: *" + DateTimeUtil.formatDate(mon) + " ~ " + DateTimeUtil.formatDate(sun) + "* 이하 마감";
      case "monthly":
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());
        return "기준월: *" + DateTimeUtil.formatDate(monthStart) + " ~ " + DateTimeUtil.formatDate(monthEnd) + "* 이하 마감";
      case "quarterly":
      default:
        return today.getYear() + " Q" + quarter
            + "  (*" + DateTimeUtil.formatDate(quarterStart)
            + " ~ " + DateTimeUtil.formatDate(quarterEnd) + "*)";
    }
  }

  /**
   * 조회 실패 등의 상황에서 사용자에게 오류 안내 DM 을 전송한다. 전송 실패 시에도 예외를 전파하지 않는다.
   */
  private void sendErrorDm(String userId, String message) {
    try {
      String dmChannelId = slackClient.openDm(userId);
      String payload = SlackBlockBuilder.forChannel(dmChannelId)
          .fallbackText(message)
          .section(message)
          .build();
      slackClient.postMessage(payload);
    } catch (Exception e) {
      log.error("현재티켓 오류 DM 전송 실패: userId={}", userId, e);
    }
  }

  /**
   * 현재티켓 조회용 경량 티켓 VO.
   * DailyReportData.TicketItem 은 scheduler 모듈 소속이라 ingest 모듈에서 직접 참조할 수 없어 별도 정의한다.
   */
  private static class TicketItem {
    final String issueKey;
    final String summary;
    final JiraStatusCode status;
    final JiraPriorityCode priority;
    final LocalDate dueDate;
    final DueDateUrgencyCode urgency;
    final String url;

    TicketItem(String issueKey, String summary,
               JiraStatusCode status, JiraPriorityCode priority,
               LocalDate dueDate, DueDateUrgencyCode urgency, String url) {
      this.issueKey = issueKey;
      this.summary = summary;
      this.status = status;
      this.priority = priority;
      this.dueDate = dueDate;
      this.urgency = urgency;
      this.url = url;
    }
  }
}
