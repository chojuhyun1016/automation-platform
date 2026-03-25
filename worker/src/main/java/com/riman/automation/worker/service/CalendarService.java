package com.riman.automation.worker.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.riman.automation.clients.calendar.GoogleCalendarClient;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import com.riman.automation.worker.service.JiraCalendarMappingService.MappingEntry;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Calendar 서비스
 *
 * <pre>
 * ══════════════════════════════════════════════════════
 * 수정 내역
 * ══════════════════════════════════════════════════════
 *
 * [FIX-4] startdate → extendedProperties 저장 (캘린더 표시 영향 없음)
 *   캘린더 이벤트 날짜: 기존과 완전 동일 (start=duedate, end=duedate+1일)
 *   startdate(customfield_10015) 처리:
 *     - 캘린더 start.date에는 반영하지 않음 → 캘린더 표시 불변
 *     - extendedProperties.private["jiraStartDate"] 에만 저장
 *     - 다른 서비스에서 Calendar API로 해당 필드를 읽어 참고 가능
 *   수정 메서드: buildExtendedProps(), buildJiraDescription()
 *   영향 없는 부분: 캘린더 이벤트 날짜 CRUD, 재택/부재, DynamoDB 매핑 — 전혀 변경 없음
 *
 * [FIX-3] DynamoDB Mapping 기반 이벤트 조회 (중복 생성 버그 수정) — 유지
 *
 * [하위 호환]
 *   startdate 없는 기존 이슈: extendedProperties에 jiraStartDate 키 없음 → 기존과 동일
 * ══════════════════════════════════════════════════════
 * </pre>
 */
@Slf4j
public class CalendarService {

    // =========================================================================
    // 상수
    // =========================================================================

    private static final String PROP_JIRA_ISSUE_KEY = "jiraIssueKey";
    private static final String PROP_ASSIGNEE_NAME = "assigneeName";
    private static final String PROP_IS_TEAM_MEMBER = "isTeamMember";
    /**
     * startdate(customfield_10015) 저장 키 — 다른 서비스에서 Calendar API로 읽어 참고용
     */
    private static final String PROP_JIRA_START_DATE = "jiraStartDate";

    // =========================================================================
    // 필드
    // =========================================================================

    private final GoogleCalendarClient calendarClient;
    private final TeamMemberService teamMemberService;

    /**
     * DynamoDB 매핑 서비스.
     * null이면 DynamoDB 매핑 없이 extendedProperties fallback만 사용 (하위 호환).
     */
    private final JiraCalendarMappingService mappingService;

    // =========================================================================
    // 생성자
    // =========================================================================

    public CalendarService() {
        this(null);
    }

    public CalendarService(ConfigService configService) {
        try {
            byte[] credBytes = loadCredentialsFromS3();
            this.calendarClient = new GoogleCalendarClient(credBytes);
            this.teamMemberService = new TeamMemberService();

            // CALENDAR_MAPPING_TABLE 환경변수가 있으면 DynamoDB 매핑 활성화
            String mappingTable = System.getenv("CALENDAR_MAPPING_TABLE");
            if (mappingTable != null && !mappingTable.isBlank()) {
                this.mappingService = new JiraCalendarMappingService();
                log.info("CalendarService 초기화 완료 (DynamoDB Mapping 활성화: {})", mappingTable);
            } else {
                this.mappingService = null;
                log.warn("CalendarService 초기화 완료 (CALENDAR_MAPPING_TABLE 미설정 → fallback 모드)");
            }
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            log.error("CalendarService 초기화 실패", e);
            throw new ConfigException("CalendarService 초기화 실패", e);
        }
    }

    // =========================================================================
    // Jira 이벤트 처리 진입점 — 기존과 동일
    // =========================================================================

    public void processJiraEvent(JiraWebhookEvent jiraEvent, ProjectRouting routing) {
        String webhookEvent = jiraEvent.getWebhookEvent();
        try {
            switch (webhookEvent) {
                case "jira:issue_created":
                    createJiraEvent(jiraEvent, routing);
                    break;
                case "jira:issue_updated":
                    updateJiraEvent(jiraEvent, routing);
                    break;
                case "jira:issue_deleted":
                    deleteJiraEvent(jiraEvent, routing);
                    break;
                default:
                    log.debug("캘린더 처리 불필요: {}", webhookEvent);
            }
        } catch (ExternalApiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("Jira 캘린더 처리 실패: issueKey={}", jiraEvent.getIssue().getKey(), e);
            throw new ExternalApiClientException("GoogleCalendar",
                    "Jira 캘린더 처리 실패: issueKey=" + jiraEvent.getIssue().getKey(), e);
        }
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    private void createJiraEvent(JiraWebhookEvent jiraEvent, ProjectRouting routing) {
        JiraWebhookEvent.Issue issue = jiraEvent.getIssue();
        JiraWebhookEvent.Fields fields = issue.getFields();

        // CREATE 조건: duedate 필수 (기존과 동일)
        if (fields.getDuedate() == null) {
            log.info("마감일 없음 → CREATE 스킵: issueKey={}", issue.getKey());
            return;
        }

        // startdate 방어 로직: null → 오늘, startdate > duedate → duedate
        fields.setStartdate(normalizeStartDate(fields.getStartdate(), fields.getDuedate()));

        TeamMember teamAssignee = findTeamAssignee(fields);
        if (teamAssignee == null) {
            log.info("비팀원 담당 → CREATE 스킵: issueKey={}", issue.getKey());
            return;
        }

        // ── 중복 생성 방지 ────────────────────────────────────────────────────
        // jira:issue_created 가 재전송되거나 Lambda 재시도 발생 시,
        // DedupeService 는 Jira webhookEventId 기준으로 걸러내지만
        // 다른 webhookEventId 로 동일 issueKey 가 재전송되면 통과하게 된다.
        // 따라서 DynamoDB/캘린더에 이미 같은 issueKey 이벤트가 있으면
        // INSERT 대신 UPDATE 로 전환하여 중복 이벤트 생성을 원천 차단한다.
        ExistingJiraEvent existing = findExistingEvent(routing.getCalendarId(), issue.getKey());
        if (existing != null) {
            log.warn("이미 존재하는 캘린더 이벤트 감지 → UPDATE 로 전환 (중복 생성 방지): " +
                    "issueKey={}, existingEventId={}", issue.getKey(), existing.eventId);
            updateJiraEvent(jiraEvent, routing);
            return;
        }

        String assigneeName = teamAssignee.getName();

        Event event = buildJiraEvent(issue, fields, assigneeName);
        event.setExtendedProperties(
                buildExtendedProps(issue.getKey(), assigneeName, true, fields.getStartdate()));

        Event created = calendarClient.insertEvent(routing.getCalendarId(), event);
        log.info("Jira 이벤트 생성: issueKey={}, eventId={}, assignee={}, startdate={}, duedate={}",
                issue.getKey(), created.getId(), assigneeName,
                fields.getStartdate(), fields.getDuedate());

        // DynamoDB 매핑 저장
        saveMapping(issue.getKey(), routing.getCalendarId(), created.getId(), assigneeName);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    private void updateJiraEvent(JiraWebhookEvent jiraEvent, ProjectRouting routing) {
        JiraWebhookEvent.Issue issue = jiraEvent.getIssue();
        JiraWebhookEvent.Fields fields = issue.getFields();
        String calendarId = routing.getCalendarId();

        // DynamoDB 우선 조회, 없으면 extendedProperties fallback
        ExistingJiraEvent existing = findExistingEvent(calendarId, issue.getKey());

        if (existing == null) {
            log.info("이벤트 없음 → CREATE 시도: issueKey={}", issue.getKey());
            createJiraEvent(jiraEvent, routing);
            return;
        }

        // duedate 제거 시 캘린더 이벤트 삭제 (기존과 동일)
        if (fields.getDuedate() == null) {
            log.info("마감일 제거 → DELETE: issueKey={}", issue.getKey());
            deleteJiraEventById(calendarId, existing.eventId, issue.getKey());
            return;
        }

        // startdate 방어 로직: null → 오늘, startdate > duedate → duedate
        fields.setStartdate(normalizeStartDate(fields.getStartdate(), fields.getDuedate()));

        TeamMember currentAssignee = findTeamAssignee(fields);

        if (currentAssignee == null) {
            // ── 담당자 비팀원 처리 (기존 로직 그대로 유지) ──────────────────
            if (existing.assigneeName == null || existing.assigneeName.isBlank()) {
                log.info("담당자 비팀원 + 이전 담당자 정보 없음 → 스킵: issueKey={}", issue.getKey());
                return;
            }

            String retainedAssigneeName = existing.assigneeName;
            log.info("담당자 비팀원, 팀원→비팀원 전환 티켓 → 기존 팀원({})으로 담당자 유지하며 업데이트: issueKey={}",
                    retainedAssigneeName, issue.getKey());

            Event existingEvent = calendarClient.listEvents(
                            calendarId,
                            LocalDate.now().minusYears(1).toString() + "T00:00:00Z",
                            LocalDate.now().plusYears(1).toString() + "T23:59:59Z",
                            null
                    ).stream()
                    .filter(e -> existing.eventId.equals(e.getId()))
                    .findFirst()
                    .orElseGet(() -> {
                        Event fallback = new Event();
                        fallback.setId(existing.eventId);
                        return fallback;
                    });

            // 캘린더 날짜: 기존과 동일하게 duedate 기준
            LocalDate dueDate = LocalDate.parse(fields.getDuedate(), DateTimeFormatter.ISO_LOCAL_DATE);

            existingEvent.setSummary(buildJiraSummary(issue.getKey(), retainedAssigneeName));
            existingEvent.setStart(new EventDateTime().setDate(new DateTime(dueDate.toString())));
            existingEvent.setEnd(new EventDateTime().setDate(new DateTime(dueDate.plusDays(1).toString())));
            existingEvent.setDescription(buildJiraDescription(issue, fields, retainedAssigneeName));
            existingEvent.setTransparency("transparent");
            existingEvent.setExtendedProperties(
                    buildExtendedProps(issue.getKey(), retainedAssigneeName, true, fields.getStartdate()));

            Event updated = calendarClient.updateEvent(calendarId, existing.eventId, existingEvent);
            log.info("비팀원 담당 중 티켓 변경 반영 완료 (담당자 유지): issueKey={}, eventId={}, assignee={}, startdate={}, duedate={}",
                    issue.getKey(), updated.getId(), retainedAssigneeName,
                    fields.getStartdate(), fields.getDuedate());

            saveMapping(issue.getKey(), calendarId, updated.getId(), retainedAssigneeName);
            return;
        }

        // ── 담당자 팀원 — 전체 정보 업데이트 ────────────────────────────────
        String assigneeName = currentAssignee.getName();
        log.info("담당자(팀원): issueKey={}, assignee={} (이전: {}, 이전팀원={}), start={}, due={}",
                issue.getKey(), assigneeName, existing.assigneeName, existing.wasTeamMember,
                fields.getStartdate(), fields.getDuedate());

        // 캘린더 날짜: 기존과 동일하게 duedate 기준
        LocalDate dueDate = LocalDate.parse(fields.getDuedate(), DateTimeFormatter.ISO_LOCAL_DATE);

        Event updatedEvent = new Event()
                .setSummary(buildJiraSummary(issue.getKey(), assigneeName))
                .setStart(new EventDateTime().setDate(new DateTime(dueDate.toString())))
                .setEnd(new EventDateTime().setDate(new DateTime(dueDate.plusDays(1).toString())))
                .setDescription(buildJiraDescription(issue, fields, assigneeName))
                .setTransparency("transparent");
        updatedEvent.setExtendedProperties(
                buildExtendedProps(issue.getKey(), assigneeName, true, fields.getStartdate()));

        Event result = calendarClient.updateEvent(calendarId, existing.eventId, updatedEvent);
        log.info("Jira 이벤트 수정 완료: issueKey={}, eventId={}, assignee={}, startdate={}, duedate={}",
                issue.getKey(), result.getId(), assigneeName,
                fields.getStartdate(), fields.getDuedate());

        saveMapping(issue.getKey(), calendarId, result.getId(), assigneeName);
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    private void deleteJiraEvent(JiraWebhookEvent jiraEvent, ProjectRouting routing) {
        JiraWebhookEvent.Issue issue = jiraEvent.getIssue();
        ExistingJiraEvent existing = findExistingEvent(routing.getCalendarId(), issue.getKey());
        if (existing == null) {
            log.info("삭제할 이벤트 없음: issueKey={}", issue.getKey());
            return;
        }
        deleteJiraEventById(routing.getCalendarId(), existing.eventId, issue.getKey());
    }

    private void deleteJiraEventById(String calendarId, String eventId, String issueKey) {
        calendarClient.deleteEvent(calendarId, eventId);
        log.info("Jira 이벤트 삭제: issueKey={}, eventId={}", issueKey, eventId);

        // DynamoDB 매핑 삭제
        deleteMapping(issueKey, calendarId);
    }

    // =========================================================================
    // DynamoDB 우선 조회 + extendedProperties fallback — 기존과 완전 동일
    // =========================================================================

    private ExistingJiraEvent findExistingEvent(String calendarId, String issueKey) {
        // 1) DynamoDB 매핑 우선 조회
        if (mappingService != null) {
            MappingEntry entry = mappingService.findMapping(issueKey, calendarId);
            if (entry != null && entry.eventId != null && !entry.eventId.isBlank()) {
                log.info("[findExistingEvent] DynamoDB hit: issueKey={}, eventId={}, assignee={}",
                        issueKey, entry.eventId, entry.assigneeName);
                return new ExistingJiraEvent(entry.eventId, entry.assigneeName, true);
            }
        }

        // 2) extendedProperties fallback (기존 이벤트 하위 호환)
        log.info("[findExistingEvent] DynamoDB miss → extendedProperties fallback: issueKey={}", issueKey);
        ExistingJiraEvent fallbackResult = findJiraEventByIssueKey(calendarId, issueKey);

        // fallback 성공 시 DynamoDB에 소급 저장
        if (fallbackResult != null && mappingService != null) {
            log.info("[findExistingEvent] fallback 성공 → DynamoDB 소급 저장: issueKey={}, eventId={}",
                    issueKey, fallbackResult.eventId);
            saveMapping(issueKey, calendarId, fallbackResult.eventId, fallbackResult.assigneeName);
        }

        return fallbackResult;
    }

    // =========================================================================
    // extendedProperties 기반 조회 — 기존과 완전 동일 (fallback 전용)
    // =========================================================================

    private ExistingJiraEvent findJiraEventByIssueKey(String calendarId, String issueKey) {
        try {
            String timeMin = LocalDate.now().minusYears(1).toString() + "T00:00:00Z";
            List<Event> events = calendarClient.listEvents(
                    calendarId, timeMin,
                    LocalDate.now().plusYears(2).toString() + "T23:59:59Z",
                    "[Jira] " + issueKey);

            for (Event event : events) {
                Event.ExtendedProperties props = event.getExtendedProperties();
                if (props == null || props.getPrivate() == null) {
                    if (event.getSummary() != null
                            && event.getSummary().startsWith("[Jira] " + issueKey + " (")) {
                        log.warn("[findJiraEventByIssueKey] extendedProperties 없음, summary 매칭으로 대체: " +
                                "eventId={}, summary={}", event.getId(), event.getSummary());
                        String assigneeName = extractAssigneeFromSummary(event.getSummary());
                        return new ExistingJiraEvent(event.getId(), assigneeName, true);
                    }
                    log.warn("extendedProperties 누락 + summary 불일치, 스킵: eventId={}", event.getId());
                    continue;
                }
                String storedKey = props.getPrivate().get(PROP_JIRA_ISSUE_KEY);
                if (!issueKey.equals(storedKey)) continue;

                String assigneeName = props.getPrivate().getOrDefault(PROP_ASSIGNEE_NAME, "");
                boolean wasTeamMember = !"false".equals(props.getPrivate().get(PROP_IS_TEAM_MEMBER));

                log.info("이벤트 발견(fallback): issueKey={}, eventId={}, assignee={}, wasTeamMember={}",
                        issueKey, event.getId(), assigneeName, wasTeamMember);
                return new ExistingJiraEvent(event.getId(), assigneeName, wasTeamMember);
            }

            log.info("캘린더 이벤트 없음: issueKey={}", issueKey);
            return null;

        } catch (Exception e) {
            log.error("Jira 이벤트 검색 실패: issueKey={}", issueKey, e);
            return null;
        }
    }

    private String extractAssigneeFromSummary(String summary) {
        int start = summary.lastIndexOf('(');
        int end = summary.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return summary.substring(start + 1, end).trim();
        }
        return "";
    }

    // =========================================================================
    // DynamoDB 매핑 헬퍼 — null guard 포함 (기존과 동일)
    // =========================================================================

    private void saveMapping(String issueKey, String calendarId,
                             String eventId, String assigneeName) {
        if (mappingService == null) return;
        mappingService.saveMapping(issueKey, calendarId, eventId, assigneeName);
    }

    private void deleteMapping(String issueKey, String calendarId) {
        if (mappingService == null) return;
        mappingService.deleteMapping(issueKey, calendarId);
    }

    // =========================================================================
    // 재택 이벤트 CRUD — 변경 없음
    // =========================================================================

    public RemoteWorkCalendarInfo findRemoteWorkEvent(String calendarId, String date) {
        try {
            String timeMin = date + "T00:00:00+09:00";
            String timeMax = date + "T23:59:59+09:00";
            List<Event> items = calendarClient.listEvents(calendarId, timeMin, timeMax, null);
            for (Event e : items) {
                if (e.getSummary() != null && e.getSummary().startsWith("재택(")) {
                    log.debug("재택 이벤트 발견: date={}, summary={}", date, e.getSummary());
                    return new RemoteWorkCalendarInfo(e.getId(), e.getSummary());
                }
            }
            log.debug("재택 이벤트 없음: date={}", date);
            return null;
        } catch (Exception e) {
            log.error("재택 이벤트 검색 실패: date={}", date, e);
            return null;
        }
    }

    public void insertRemoteWorkEvent(String calendarId, Event event) throws Exception {
        Event created = calendarClient.insertEvent(calendarId, event);
        log.info("재택 이벤트 생성: id={}, summary={}", created.getId(), created.getSummary());
    }

    public void updateRemoteWorkEventTitle(String calendarId, String eventId,
                                           String newTitle) throws Exception {
        List<Event> events = calendarClient.listEvents(
                calendarId,
                LocalDate.now().minusDays(30).toString() + "T00:00:00+09:00",
                LocalDate.now().plusDays(30).toString() + "T23:59:59+09:00",
                null);
        Event existing = events.stream()
                .filter(e -> eventId.equals(e.getId()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            existing = new Event();
            existing.setId(eventId);
        }
        existing.setSummary(newTitle);
        Event updated = calendarClient.updateEvent(calendarId, eventId, existing);
        log.info("재택 이벤트 제목 수정: id={}, newTitle={}", eventId, updated.getSummary());
    }

    public void deleteRemoteWorkEvent(String calendarId, String eventId) throws Exception {
        calendarClient.deleteEvent(calendarId, eventId);
        log.info("재택 이벤트 삭제: id={}", eventId);
    }

    // =========================================================================
    // 부재 이벤트 CRUD — 변경 없음
    // =========================================================================

    public List<Event> listCalendarEvents(String calendarId,
                                          String timeMinRfc3339,
                                          String timeMaxRfc3339,
                                          String searchQuery) {
        return calendarClient.listEvents(calendarId, timeMinRfc3339, timeMaxRfc3339, searchQuery);
    }

    public Event insertCalendarEvent(String calendarId, Event event) {
        Event created = calendarClient.insertEvent(calendarId, event);
        log.info("부재 이벤트 생성: id={}, summary={}", created.getId(), created.getSummary());
        return created;
    }

    public void deleteCalendarEvent(String calendarId, String eventId) {
        calendarClient.deleteEvent(calendarId, eventId);
        log.info("부재 이벤트 삭제: eventId={}", eventId);
    }

    // =========================================================================
    // 내부 헬퍼 — buildJiraEvent 수정, 나머지 기존과 동일
    // =========================================================================

    private TeamMember findTeamAssignee(JiraWebhookEvent.Fields fields) {
        if (fields.getAssignee() == null || fields.getAssignee().getAccountId() == null)
            return null;
        return teamMemberService.findByAccountId(fields.getAssignee().getAccountId());
    }

    private String buildJiraSummary(String issueKey, String assigneeName) {
        return "[Jira] " + issueKey + " (" + assigneeName + ")";
    }

    /**
     * Jira 이벤트 설명 생성.
     *
     * <p>startdate(customfield_10015)가 있으면 "Start Date: yyyy-MM-dd" 항목을 추가한다.
     * description은 캘린더 이벤트 상세보기에서 사람이 확인하는 용도.
     * (캘린더 이벤트 날짜 자체에는 영향 없음)
     */
    private String buildJiraDescription(JiraWebhookEvent.Issue issue,
                                        JiraWebhookEvent.Fields fields,
                                        String assigneeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Jira Ticket: ").append(issue.getKey()).append("\n");
        sb.append("Title: ").append(fields.getSummary()).append("\n\n");
        sb.append("Assignee: ").append(assigneeName).append("\n");
        if (fields.getPriority() != null)
            sb.append("Priority: ").append(fields.getPriority().getName()).append("\n");
        if (fields.getStatus() != null)
            sb.append("Status: ").append(fields.getStatus().getName()).append("\n");
        if (fields.getProject() != null)
            sb.append("Project: ").append(fields.getProject().getName()).append("\n");

        // startdate가 있을 때만 표시 (description에 참고용으로 포함)
        if (fields.hasStartDate()) {
            sb.append("Start Date: ").append(fields.getStartdate()).append("\n");
        }
        if (fields.getDuedate() != null) {
            sb.append("Due Date: ").append(fields.getDuedate()).append("\n");
        }

        sb.append("\nView in Jira:\nhttps://riman-it.atlassian.net/browse/").append(issue.getKey());
        return sb.toString();
    }

    /**
     * Google Calendar Event 객체 생성.
     *
     * <pre>
     * 캘린더 이벤트 날짜 — 기존과 완전 동일:
     *   start.date = duedate
     *   end.date   = duedate + 1일  (Google All-day 이벤트 exclusive end 규칙)
     *   → 캘린더 화면: duedate 당일 하루짜리 종일 이벤트
     *
     * startdate(customfield_10015) 처리:
     *   - 캘린더 날짜에 반영하지 않음 → 캘린더 표시 불변
     *   - extendedProperties.private["jiraStartDate"] 에 저장
     *   - description에 "Start Date: yyyy-MM-dd" 텍스트로 포함
     *   → 다른 서비스에서 Calendar API로 읽어 참고 가능
     * </pre>
     */
    private Event buildJiraEvent(JiraWebhookEvent.Issue issue,
                                 JiraWebhookEvent.Fields fields,
                                 String assigneeName) {
        // 캘린더 날짜: 기존과 동일하게 duedate 기준
        LocalDate dueDate = LocalDate.parse(fields.getDuedate(), DateTimeFormatter.ISO_LOCAL_DATE);

        return new Event()
                .setSummary(buildJiraSummary(issue.getKey(), assigneeName))
                .setDescription(buildJiraDescription(issue, fields, assigneeName))
                .setStart(new EventDateTime().setDate(new DateTime(dueDate.toString())))
                .setEnd(new EventDateTime().setDate(new DateTime(dueDate.plusDays(1).toString())))
                .setTransparency("transparent");
    }

    /**
     * startdate 방어 로직.
     * <ol>
     *   <li>startdate가 null/blank → 오늘 날짜로 대체</li>
     *   <li>startdate > duedate → duedate로 보정 (1번 적용 후에도 재검증)</li>
     * </ol>
     */
    private String normalizeStartDate(String startdate, String duedate) {
        if (startdate == null || startdate.isBlank()) {
            startdate = LocalDate.now().toString();
        }
        if (duedate != null && !duedate.isBlank()) {
            LocalDate start = LocalDate.parse(startdate, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate due = LocalDate.parse(duedate, DateTimeFormatter.ISO_LOCAL_DATE);
            if (start.isAfter(due)) {
                startdate = duedate;
            }
        }
        return startdate;
    }

    private Event.ExtendedProperties buildExtendedProps(String issueKey, String assigneeName,
                                                        boolean isTeamMember,
                                                        String startDate) {
        Map<String, String> props = new HashMap<>();
        props.put(PROP_JIRA_ISSUE_KEY, issueKey);
        props.put(PROP_ASSIGNEE_NAME, assigneeName);
        props.put(PROP_IS_TEAM_MEMBER, String.valueOf(isTeamMember));
        // startdate(customfield_10015) 저장 — 다른 서비스에서 Calendar API로 읽어 참고
        if (startDate != null && !startDate.isBlank()) {
            props.put(PROP_JIRA_START_DATE, startDate);
        }
        Event.ExtendedProperties extProps = new Event.ExtendedProperties();
        extProps.setPrivate(props);
        return extProps;
    }

    private byte[] loadCredentialsFromS3() {
        String bucket = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_BUCKET");
        String key = System.getenv("GOOGLE_CALENDAR_CREDENTIALS_KEY");
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new ConfigException(
                    "GOOGLE_CALENDAR_CREDENTIALS_BUCKET 또는 GOOGLE_CALENDAR_CREDENTIALS_KEY 미설정");
        }
        try {
            S3Client s3 = S3Client.builder().build();
            ResponseInputStream<GetObjectResponse> response = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response.readAllBytes();
        } catch (Exception e) {
            log.error("S3에서 credentials 로드 실패: bucket={}, key={}", bucket, key, e);
            throw new ConfigException("Google Calendar credentials S3 로드 실패", e);
        }
    }

    // =========================================================================
    // 내부 타입 — 기존과 동일
    // =========================================================================

    public static class RemoteWorkCalendarInfo {
        public final String eventId;
        public final String summary;

        public RemoteWorkCalendarInfo(String eventId, String summary) {
            this.eventId = eventId;
            this.summary = summary;
        }
    }

    private static class ExistingJiraEvent {
        final String eventId;
        final String assigneeName;
        final boolean wasTeamMember;

        ExistingJiraEvent(String eventId, String assigneeName, boolean wasTeamMember) {
            this.eventId = eventId;
            this.assigneeName = assigneeName;
            this.wasTeamMember = wasTeamMember;
        }
    }
}
