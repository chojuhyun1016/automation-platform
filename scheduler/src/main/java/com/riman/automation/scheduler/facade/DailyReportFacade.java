package com.riman.automation.scheduler.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.code.ReportPeriodCode;
import com.riman.automation.common.exception.ConfigException;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.s3.DailyReportConfig;
import com.riman.automation.scheduler.dto.report.DailyReportData;
import com.riman.automation.scheduler.dto.report.DailyReportData.ScheduleItem;
import com.riman.automation.scheduler.dto.s3.TeamMember;
import com.riman.automation.scheduler.service.collect.DailyCalendarTicketCollector;
import com.riman.automation.scheduler.service.collect.DailyScheduleCollector;
import com.riman.automation.scheduler.service.load.TeamMemberService;
import com.riman.automation.scheduler.service.collect.DailyAbsenceCollector;
import com.riman.automation.scheduler.service.report.DailyReportService;
import com.riman.automation.scheduler.service.format.DailyReportFormatter;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.riman.automation.scheduler.dto.s3.AnnouncementItem;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 일일 보고서 오케스트레이터 (팀원별 개인 DM 전송 방식)
 *
 * <p><b>WeeklyReportFacade 와의 대응:</b>
 * <pre>
 *   DailyReportFacade   — 일일 보고서 (Slack DM)
 *   WeeklyReportFacade  — 주간 실적 보고서 (Confluence 페이지)
 * </pre>
 *
 * <p><b>흐름:</b>
 * <pre>
 *   team-members.json 로드 → 팀원별 순회
 *     └─ Google Calendar에서 이 팀원이 담당인 티켓 수집
 *     └─ 부재/재택 수집 (전 팀원 공통)
 *     └─ conversations.open → 개인 DM 채널
 *     └─ 개인 DM으로 보고서 전송
 * </pre>
 *
 * <p><b>설정 파일 (S3):</b>
 * <pre>
 *   scheduler-config.json → dailyReport 섹션
 *     calendar_id          : 부재/재택 캘린더 ID
 *     ticket_calendar_id   : 티켓 이벤트 캘린더 ID
 *     schedule_calendar_id : /일정등록 이벤트 캘린더 ID (미설정 시 오늘 일정 섹션 미출력)
 *     announcements        : [공지]
 *     links                : [{title, url}]
 *
 *   team-members.json → 팀원 목록
 *     [{name, slack_user_id, calendar_name, jira_account_id, enabled}]
 * </pre>
 */
@Slf4j
public class DailyReportFacade {

    private static final ObjectMapper OM = new ObjectMapper();

    private final S3Client s3Client;
    private final String configBucket;
    private final String configKey;
    private final TeamMemberService teamMemberService;
    private final DailyCalendarTicketCollector ticketCollector;
    private final DailyAbsenceCollector absenceCollector;
    private final DailyScheduleCollector scheduleCollector;
    private final DailyReportFormatter formatter;
    private final DailyReportService aiRefiner;
    private final SlackClient slackClient;

    /**
     * scheduleCollector 가 null 이면 오늘 일정 섹션 미출력 (기존 동작과 동일).
     */
    public DailyReportFacade(
            S3Client s3Client, String configBucket, String configKey,
            TeamMemberService teamMemberService,
            DailyCalendarTicketCollector ticketCollector,
            DailyAbsenceCollector absenceCollector,
            DailyScheduleCollector scheduleCollector,
            DailyReportFormatter formatter,
            DailyReportService aiRefiner,
            SlackClient slackClient) {

        this.s3Client = s3Client;
        this.configBucket = configBucket;
        this.configKey = configKey;
        this.teamMemberService = teamMemberService;
        this.ticketCollector = ticketCollector;
        this.absenceCollector = absenceCollector;
        this.scheduleCollector = scheduleCollector;
        this.formatter = formatter;
        this.aiRefiner = aiRefiner;
        this.slackClient = slackClient;
    }

    // =========================================================================
    // 일일 보고서 실행
    // =========================================================================

    public void runDaily() {
        runDaily(DateTimeUtil.todayKst());
    }

    public void runDaily(LocalDate baseDate) {
        log.info("[DailyReportFacade] 일일 보고서 시작: baseDate={}", baseDate);

        LocalDate today = baseDate;
        DailyReportConfig config = loadConfig("dailyReport");

        if (Boolean.FALSE.equals(config.getEnabled())) {
            log.info("[DailyReportFacade] 일일 보고서 비활성화됨 (enabled=false)");
            return;
        }

        List<TeamMember> members = teamMemberService.loadEnabled();
        if (members.isEmpty()) {
            log.warn("[DailyReportFacade] 활성 팀원 없음, 종료");
            return;
        }

        // 부재/재택 — 전 팀원 공통 1회 수집
        List<DailyReportData.AbsenceItem> allAbsences =
                absenceCollector.collect(config.getCalendarId(), config.getTicketCalendarId(), today);

        // 티켓 — 캘린더 1회 조회 후 팀원별 분류
        Map<TeamMember, List<DailyReportData.TicketItem>> ticketsByMember =
                ticketCollector.collectAllMembers(config.getTicketCalendarId(), members, today);

        // 오늘 일정 — /일정등록 커맨드로 등록된 당일 일정
        Map<String, List<ScheduleItem>> schedulesBySlackId =
                collectTodaySchedules(config, members, today);

        // Manager 총괄 보고서용 팀별 Engineer 목록
        Map<String, List<TeamMember>> engineersByTeam = new LinkedHashMap<>();
        for (TeamMember m : members) {
            if (m.isEngineer() && m.getTeam() != null) {
                engineersByTeam.computeIfAbsent(m.getTeam(), k -> new java.util.ArrayList<>()).add(m);
            }
        }

        int success = 0, fail = 0;
        for (TeamMember member : members) {
            try {
                LinkedHashMap<String, List<DailyReportData.TicketItem>> teamTickets = null;
                if (member.isManager() && member.getTeam() != null) {
                    teamTickets = new LinkedHashMap<>();
                    List<TeamMember> sameTeamEngineers =
                            engineersByTeam.getOrDefault(member.getTeam(), List.of());
                    for (TeamMember eng : sameTeamEngineers) {
                        teamTickets.put(eng.getName(),
                                ticketsByMember.getOrDefault(eng, List.of()));
                    }
                    log.info("[DailyReportFacade] Manager 총괄 티켓 구성: manager={}, engineers={}명",
                            member.getName(), teamTickets.size());
                }

                List<ScheduleItem> todaySchedules = schedulesBySlackId
                        .getOrDefault(member.getSlackUserId(), List.of());

                sendToMember(member, today, config, allAbsences,
                        ticketsByMember.getOrDefault(member, List.of()),
                        teamTickets, todaySchedules);
                success++;
            } catch (Exception e) {
                log.error("[DailyReportFacade] DM 전송 실패: member={}", member.getName(), e);
                fail++;
            }
        }

        log.info("[DailyReportFacade] 일일 보고서 완료: 성공={}, 실패={}", success, fail);
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private void sendToMember(
            TeamMember member,
            LocalDate today,
            DailyReportConfig config,
            List<DailyReportData.AbsenceItem> allAbsences,
            List<DailyReportData.TicketItem> memberTickets,
            LinkedHashMap<String, List<DailyReportData.TicketItem>> teamTickets,
            List<ScheduleItem> todaySchedules) {

        String dmChannelId = slackClient.openDm(member.getSlackUserId());
        log.info("[DailyReportFacade] DM 채널: member={}, channel={}", member.getName(), dmChannelId);

        List<AnnouncementItem> activeAnnouncements = loadAnnouncements(config, today);

        DailyReportData data = DailyReportData.builder()
                .baseDate(today)
                .period(ReportPeriodCode.DAILY)
                .announcements(activeAnnouncements)
                .absences(allAbsences)
                .tickets(memberTickets)
                .teamTickets(teamTickets)
                .todaySchedules(todaySchedules)
                .links(config.getLinks() == null ? List.of() :
                        config.getLinks().stream()
                                .map(l -> DailyReportData.PageLinkItem.builder()
                                        .title(l.getTitle()).url(l.getUrl()).build())
                                .toList())
                .build();

        String payload = (aiRefiner != null)
                ? aiRefiner.refineAndFormat(dmChannelId, data, config)
                : formatter.format(dmChannelId, data, config);

        String ts = slackClient.postMessage(payload);
        log.info("[DailyReportFacade] DM 전송 완료: member={}, ts={}, tickets={}건, schedules={}건",
                member.getName(), ts, memberTickets.size(), todaySchedules.size());
    }

    private Map<String, List<ScheduleItem>> collectTodaySchedules(
            DailyReportConfig config, List<TeamMember> members, LocalDate today) {

        if (scheduleCollector == null) {
            log.debug("[DailyReportFacade] DailyScheduleCollector 미초기화 → 오늘 일정 생략");
            return Map.of();
        }
        String scheduleCalendarId = config.getScheduleCalendarId();
        if (scheduleCalendarId == null || scheduleCalendarId.isBlank()) {
            log.info("[DailyReportFacade] schedule_calendar_id 미설정 → 오늘 일정 생략");
            return Map.of();
        }
        try {
            Map<String, List<ScheduleItem>> result =
                    scheduleCollector.collectAllMembers(scheduleCalendarId, members, today);
            log.info("[DailyReportFacade] 오늘 일정 수집 완료: {}명", result.size());
            return result;
        } catch (Exception e) {
            log.error("[DailyReportFacade] 오늘 일정 수집 실패, 생략하고 계속: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    private List<AnnouncementItem> loadAnnouncements(DailyReportConfig config, LocalDate today) {
        String key = config.getAnnouncementsKey();
        if (key == null || key.isBlank()) {
            log.debug("[DailyReportFacade] announcements_key 미설정 → 공지 없음");
            return List.of();
        }
        try {
            log.info("[DailyReportFacade] announcements 로드: {}/{}", configBucket, key);
            byte[] bytes = s3Client.getObject(
                    GetObjectRequest.builder().bucket(configBucket).key(key).build()
            ).readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            List<AnnouncementItem> all = OM.readValue(
                    json, OM.getTypeFactory().constructCollectionType(List.class, AnnouncementItem.class));
            List<AnnouncementItem> active = all.stream()
                    .filter(a -> a.getMessage() != null && !a.getMessage().isBlank())
                    .filter(a -> a.isActive(today))
                    .toList();
            log.info("[DailyReportFacade] announcements 완료: 전체={}건, 노출={}건",
                    all.size(), active.size());
            return active;
        } catch (Exception e) {
            log.warn("[DailyReportFacade] announcements 로드 실패, 공지 없음으로 처리: key={}, err={}",
                    key, e.getMessage());
            return List.of();
        }
    }

    private DailyReportConfig loadConfig(String section) {
        try {
            log.info("[DailyReportFacade] scheduler-config 로드: {}/{}", configBucket, configKey);
            byte[] bytes = s3Client.getObject(
                    GetObjectRequest.builder().bucket(configBucket).key(configKey).build()
            ).readAllBytes();
            JsonNode root = OM.readTree(new String(bytes, StandardCharsets.UTF_8));
            JsonNode node = root.path(section);
            if (node.isMissingNode() || node.isNull()) {
                throw new ConfigException(
                        "scheduler-config.json 에 '" + section + "' 섹션 없음: "
                                + configBucket + "/" + configKey);
            }
            return OM.treeToValue(node, DailyReportConfig.class);
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException(
                    "scheduler-config.json 로드 실패: " + configBucket + "/" + configKey, e);
        }
    }
}
