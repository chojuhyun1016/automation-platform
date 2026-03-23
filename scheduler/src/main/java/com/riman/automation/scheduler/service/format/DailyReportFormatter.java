package com.riman.automation.scheduler.service.format;

import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.common.code.JiraStatusCode;
import com.riman.automation.common.code.ReportWeekCode;
import com.riman.automation.common.code.WorkStatusCode;
import com.riman.automation.common.util.DateTimeUtil;
import com.riman.automation.scheduler.dto.report.DailyReportData;
import com.riman.automation.scheduler.dto.s3.DailyReportConfig;
import com.riman.automation.scheduler.dto.report.DailyReportData.AbsenceItem;
import com.riman.automation.scheduler.dto.s3.AnnouncementItem;
import com.riman.automation.scheduler.dto.report.DailyReportData.TicketItem;
import com.riman.automation.scheduler.dto.report.DailyReportData.ScheduleItem;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 일일 보고서 포맷터 — Slack Block Kit (mrkdwn section)
 *
 * <p><b>들여쓰기 규칙:</b>
 * <ul>
 *   <li>섹션 내용 1단계: {@code INDENT} (전각공백 2개)</li>
 *   <li>날짜 헤더 2단계: {@code INDENT2} (전각공백 3개, 금요일 금주/차주 하위)</li>
 *   <li>티켓 행 3단계: {@code INDENT3} (전각공백 4개, 날짜 헤더 하위)</li>
 * </ul>
 *
 * <p><b>날짜 형식:</b> {@code MM/DD(요일)} — 한 자리 월/일은 앞에 0 추가 (예: 02/07)
 *
 * <p><b>미완료 티켓 현황 규칙:</b>
 * <ul>
 *   <li>조건: dueDate &lt; 이번 주 월요일 AND status != DONE</li>
 *   <li>이슈키: Slack 링크 {@code <url|[KEY]>}</li>
 *   <li>제목: 인라인 백틱으로 감싸 색상 강조 {@code `제목`}</li>
 * </ul>
 *
 * <p><b>티켓 현황 규칙:</b>
 * <ul>
 *   <li>금요일: [금주] / [차주] 헤더 분리</li>
 *   <li>기한 초과 + 미완료 → bold</li>
 * </ul>
 *
 * <p><b>[추가] 부재 & 재택 섹션 표시 규칙:</b>
 * <ul>
 *   <li>WorkStatusCode.UNKNOWN 마커 타입: memberName에 "이름(부재타입)"이 이미 포함 → 그대로 표시</li>
 *   <li>기타 타입(재택 등): 기존 방식 그대로 memberName + "(" + workStatus.getDisplayName() + ")"</li>
 * </ul>
 *
 * <p><b>[추가] 오늘 일정 섹션:</b>
 * <ul>
 *   <li>위치: 팀원 미완료 티켓 현황 다음, 주요 페이지 이전</li>
 *   <li>대상: /일정등록 커맨드로 등록된 본인 당일 일정</li>
 *   <li>정렬: 종일 일정 우선 → 시작시간 오름차순</li>
 *   <li>포맷: {@code [HH:mm-HH:mm] 제목} 또는 {@code [종일] 제목}</li>
 *   <li>URL 있으면 제목을 Slack 링크({@code <url|제목>})로 표시</li>
 * </ul>
 */
@Slf4j
public class DailyReportFormatter {

    /**
     * 섹션 내용 1단계 들여쓰기 — 전각공백 2개
     */
    private static final String INDENT = "\u3000\u3000";

    /**
     * 날짜 헤더 2단계 들여쓰기 — 전각공백 3개 (금요일 금주/차주 하위)
     */
    private static final String INDENT2 = "\u3000\u3000\u3000";

    /**
     * 티켓 행 3단계 들여쓰기 — 전각공백 4개
     */
    private static final String INDENT3 = "\u3000\u3000\u3000\u3000";

    private static final String[] DAY_KOR = {"월", "화", "수", "목", "금"};
    private static final String[] DAY_KOR_ALL = {"월", "화", "수", "목", "금", "토", "일"};


    // =========================================================================
    // 공개 API
    // =========================================================================

    public String format(String channelId, DailyReportData data, DailyReportConfig config) {
        LocalDate baseDate = data.getBaseDate();
        ReportWeekCode weekCode = ReportWeekCode.from(baseDate);

        SlackBlockBuilder builder = SlackBlockBuilder
                .forChannel(channelId)
                .fallbackText("📊 일일 팀 보고서 | " + DateTimeUtil.formatDisplay(baseDate))
                .noUnfurl();

        builder.header("📊 일일 팀 보고서  |  " + DateTimeUtil.formatDisplay(baseDate)
                + "   " + weekCode.getDisplayName());

        appendAnnouncements(builder, data.getAnnouncements());
        appendLinks(builder, config.getLinks());

        builder.divider();
        builder.section(buildAbsenceText(data.getAbsences(), baseDate));

        builder.divider();
        appendTicketSection(builder, data.getTickets(), baseDate);

        // Manager 전용: 팀원 티켓 현황 — 티켓 현황 바로 아래
        if (data.getTeamTickets() != null && !data.getTeamTickets().isEmpty()) {
            builder.divider();
            appendTeamTicketSection(builder, data.getTeamTickets(), baseDate);
        }

        builder.divider();
        appendOverdueTicketSection(builder, data.getTickets(), baseDate);

        // Manager 전용: 팀원 미완료 티켓 현황 — 미완료 티켓 현황 바로 아래
        if (data.getTeamTickets() != null && !data.getTeamTickets().isEmpty()) {
            builder.divider();
            appendTeamOverdueSection(builder, data.getTeamTickets(), baseDate);
        }

        // [추가] 오늘 일정 — 팀원 미완료 티켓 현황 다음
        appendTodayScheduleSection(builder, data.getTodaySchedules());

        builder.context("_발송: " + DateTimeUtil.formatDateTime(DateTimeUtil.nowKst()) + " KST_");

        log.info("[DailyReportFormatter] 포맷 완료: channel={}, date={}", channelId, baseDate);
        return builder.build();
    }

    public String formatAsPlainText(DailyReportData data, DailyReportConfig config) {
        LocalDate baseDate = data.getBaseDate();
        ReportWeekCode weekCode = ReportWeekCode.from(baseDate);
        StringBuilder sb = new StringBuilder();

        sb.append("# 📊 일일 팀 보고서 | ").append(DateTimeUtil.formatDisplay(baseDate))
                .append("  (").append(weekCode.getDisplayName()).append(")\n\n");

        sb.append("## 📢 팀 공지\n");
        if (data.getAnnouncements() == null || data.getAnnouncements().isEmpty()) {
            sb.append("없음\n\n");
        } else {
            data.getAnnouncements().forEach(a -> sb.append("• ").append(a.getMessage()).append("\n"));
            sb.append("\n");
        }

        if (config.getLinks() != null && !config.getLinks().isEmpty()) {
            sb.append("## 🔗 주요 페이지\n");
            config.getLinks().forEach(l -> sb.append("• ").append(l.getTitle())
                    .append(": ").append(l.getUrl()).append("\n"));
            sb.append("\n");
        }

        sb.append("## 🏠 부재 & 재택\n");
        sb.append(buildAbsencePlainText(data.getAbsences(), baseDate)).append("\n\n");

        // 미완료(과거 기한 초과) / 금주차주 분리
        LocalDate monday4Plain = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<TicketItem> overdueForPlain = data.getTickets() == null ? List.of() :
                data.getTickets().stream()
                        .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(monday4Plain)
                                && t.getStatus() != JiraStatusCode.DONE)
                        .sorted(Comparator.comparing(TicketItem::getDueDate))
                        .collect(Collectors.toList());
        List<TicketItem> currentForPlain = data.getTickets() == null ? List.of() :
                data.getTickets().stream()
                        .filter(t -> t.getDueDate() == null || !t.getDueDate().isBefore(monday4Plain))
                        .collect(Collectors.toList());

        if (!overdueForPlain.isEmpty()) {
            sb.append("## 🚨 미완료 티켓 현황\n");
            sb.append(buildTicketPlainText(overdueForPlain)).append("\n\n");
        }

        sb.append("## 🎫 티켓 현황\n");
        sb.append(buildTicketPlainText(currentForPlain)).append("\n\n");

        // [추가] 오늘 일정 plain text (AI 후처리용)
        List<ScheduleItem> schedules = data.getTodaySchedules();
        sb.append("## 📅 오늘 일정\n");
        if (schedules == null || schedules.isEmpty()) {
            sb.append("없음\n\n");
        } else {
            schedules.forEach(s -> sb.append("• ").append(formatScheduleRowPlain(s)).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }


    // =========================================================================
    // 공지 섹션
    // =========================================================================

    /**
     * 팀 공지 섹션 추가
     *
     * <p><b>공지 한 줄 포맷 규칙 (우선순위 순):</b>
     * <ol>
     *   <li><b>URL 있음</b>: URL 링크({@code <url|text>})가 최우선.
     *       type이 있으면 링크 텍스트(message)에만 강조 적용.
     *       <ul>
     *         <li>{@code bold}: {@code <url|*message*>}</li>
     *         <li>{@code red} : {@code <url|`message`>}</li>
     *         <li>미설정   : {@code <url|message>} (기존 동작 유지)</li>
     *       </ul>
     *   </li>
     *   <li><b>URL 없음</b>: type에 따라 message에 직접 강조 적용.
     *       <ul>
     *         <li>{@code bold}: {@code *message*}</li>
     *         <li>{@code red} : {@code `message`}</li>
     *         <li>미설정   : plain message (기존 동작 유지)</li>
     *       </ul>
     *   </li>
     * </ol>
     */
    private void appendAnnouncements(SlackBlockBuilder builder,
                                     List<AnnouncementItem> announcements) {
        builder.divider();
        if (announcements == null || announcements.isEmpty()) {
            builder.section("*📢 팀 공지*\n" + INDENT + "_없음_");
            return;
        }

        StringBuilder sb = new StringBuilder("*📢 팀 공지*\n");
        for (AnnouncementItem a : announcements) {
            sb.append(INDENT).append("• ").append(formatAnnouncementLine(a)).append("\n");
        }
        builder.section(sb.toString().trim());
    }

    /**
     * 공지 항목 한 줄 포맷 — URL 최우선, type에 따른 강조 적용
     *
     * <p>URL이 있으면 Slack 링크({@code <url|text>})로 감싸며,
     * type 강조는 링크 텍스트(message)에만 적용한다.
     * URL이 없으면 message 텍스트 자체에 type 강조를 적용한다.
     *
     * <p>기존 동작(type 미설정 시 plain 출력)은 변경하지 않는다.
     *
     * @param a 공지 항목
     * @return 포맷된 Slack mrkdwn 문자열 (한 줄)
     */
    private String formatAnnouncementLine(AnnouncementItem a) {
        // type에 따른 message 강조 텍스트 생성
        // bold: *message*, red: `message`, 미설정: message (기존 동작 유지)
        String displayText;
        if (a.isBold()) {
            displayText = "*" + a.getMessage() + "*";
        } else if (a.isRed()) {
            displayText = "`" + a.getMessage() + "`";
        } else {
            // type 미설정 — 기존 동작 그대로 plain 텍스트
            displayText = a.getMessage();
        }

        if (a.hasUrl()) {
            // URL 최우선: 링크로 감싸되, 링크 텍스트에 type 강조 적용
            return "<" + a.getUrl() + "|" + displayText + ">";
        } else {
            // URL 없음: displayText 그대로 출력
            return displayText;
        }
    }


    // =========================================================================
    // 부재 & 재택 섹션
    // =========================================================================

    private String buildAbsenceText(List<AbsenceItem> absences, LocalDate baseDate) {
        Map<LocalDate, List<AbsenceItem>> byDate = (absences == null || absences.isEmpty())
                ? Map.of()
                : absences.stream().collect(Collectors.groupingBy(
                AbsenceItem::getDate, LinkedHashMap::new, Collectors.toList()));

        boolean isFriday = DateTimeUtil.isFriday(baseDate);
        LocalDate monday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        StringBuilder sb = new StringBuilder("*🏠 부재 & 재택*\n");

        if (isFriday) {
            sb.append(INDENT).append("*[ 금주 ]*\n");
            for (int i = 0; i < 5; i++) {
                LocalDate day = monday.plusDays(i);
                sb.append(buildAbsenceRow(day, byDate, baseDate, INDENT2));
            }
            sb.append("\n");
            sb.append(INDENT).append("*[ 차주 ]*\n");
            LocalDate nextMonday = monday.plusWeeks(1);
            for (int i = 0; i < 5; i++) {
                LocalDate day = nextMonday.plusDays(i);
                sb.append(buildAbsenceRow(day, byDate, baseDate, INDENT2));
            }
        } else {
            for (int i = 0; i < 5; i++) {
                LocalDate day = monday.plusDays(i);
                sb.append(buildAbsenceRow(day, byDate, baseDate, INDENT));
            }
        }

        return sb.toString().trim();
    }

    /**
     * 날짜별 부재 & 재택 한 줄 포맷
     *
     * <p>기존 로직: memberName + "(" + workStatus.getDisplayName() + ")" 형식으로 표시
     *
     * <p>[추가] WorkStatusCode.UNKNOWN 마커 타입(AbsenceTypeCode 파싱 결과)은
     * CalendarCollector에서 이미 memberName을 "이름(부재타입)" 형식으로 저장했으므로 그대로 표시한다.
     * (예: "홍길동(연차)", "김철수(오전 반차)")
     * 이 경우 workStatus.getDisplayName()을 추가하지 않는다.
     *
     * <p>기존 타입(REMOTE 등)은 기존 방식 그대로 표시한다.
     * (예: "홍길동(재택)")
     */
    private String buildAbsenceRow(LocalDate day, Map<LocalDate, List<AbsenceItem>> byDate,
                                   LocalDate baseDate, String indent) {
        String label = DAY_KOR[day.getDayOfWeek().getValue() - 1]
                + "(" + String.format("%02d", day.getMonthValue())
                + "/" + String.format("%02d", day.getDayOfMonth()) + ") : ";

        List<AbsenceItem> items = byDate.getOrDefault(day, List.of());

        String people = items.isEmpty()
                ? "-"
                : items.stream()
                .map(a -> {
                    // [추가] AbsenceTypeCode 파싱 항목: CalendarCollector에서 memberName을
                    // "이름(부재타입)" 형식으로 저장(WorkStatusCode.UNKNOWN 마커 사용) → 그대로 사용
                    // memberName에 이미 "(부재타입)"이 포함되어 있으므로 추가 가공 불필요
                    if (a.getWorkStatus() == WorkStatusCode.UNKNOWN) {
                        return a.getMemberName();
                    }
                    // 기존 타입(REMOTE 등): memberName + "(" + displayName + ")"
                    return a.getMemberName() + "(" + a.getWorkStatus().getDisplayName() + ")";
                })
                .collect(Collectors.joining(", "));

        String row = label + people;
        boolean isToday = day.equals(baseDate);
        boolean hasPeople = !items.isEmpty();
        String line = indent + (isToday && hasPeople ? "*" + row + "*" : row) + "\n";
        return line;
    }

    private String buildAbsencePlainText(List<AbsenceItem> absences, LocalDate baseDate) {
        Map<LocalDate, List<AbsenceItem>> byDate = (absences == null || absences.isEmpty())
                ? Map.of()
                : absences.stream().collect(Collectors.groupingBy(
                AbsenceItem::getDate, LinkedHashMap::new, Collectors.toList()));

        boolean isFriday = DateTimeUtil.isFriday(baseDate);
        LocalDate monday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        StringBuilder sb = new StringBuilder();

        if (isFriday) sb.append("  [ 금주 ]\n");
        for (int i = 0; i < 5; i++) {
            LocalDate day = monday.plusDays(i);
            sb.append(buildAbsenceRow(day, byDate, baseDate, "  "));
        }

        if (isFriday) {
            sb.append("\n  [ 차주 ]\n");
            LocalDate nextMonday = monday.plusWeeks(1);
            for (int i = 0; i < 5; i++) {
                LocalDate day = nextMonday.plusDays(i);
                sb.append(buildAbsenceRow(day, byDate, baseDate, "  "));
            }
        }

        return sb.toString().trim();
    }


    // =========================================================================
    // 🚨 미완료 티켓 현황 섹션
    // =========================================================================

    /**
     * 미완료 티켓 현황 섹션 추가
     *
     * <p>조건: dueDate &lt; 이번 주 월요일 AND status != DONE
     * <p>정렬: dueDate 오름차순
     *
     * <p>포맷 (행):
     * <pre>
     *   🚨 미완료 티켓 현황
     *   　　　*02/17(화)*
     *   　　　　　• <url|[CCE-2301]> `미처리 항목 A`
     *   　　　*02/20(금)*
     *   　　　　　• <url|[RBO-5310]> `미처리 항목 B`
     * </pre>
     *
     * <p>이슈키: Slack 링크 {@code <url|[KEY]>} (클릭 가능)
     * <p>제목: 인라인 백틱 {@code `제목`} — 회색 강조로 색상 구분
     */
    private void appendOverdueTicketSection(SlackBlockBuilder builder,
                                            List<TicketItem> tickets, LocalDate baseDate) {
        LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 조건: dueDate < 금주 월요일 AND 미완료(DONE 아님)
        List<TicketItem> overdue = (tickets == null) ? List.of() : tickets.stream()
                .filter(t -> t.getDueDate() != null
                        && t.getDueDate().isBefore(thisMonday)
                        && t.getStatus() != JiraStatusCode.DONE)
                .sorted(Comparator.comparing(TicketItem::getDueDate)
                        .thenComparing(TicketItem::getIssueKey))
                .collect(Collectors.toList());

        if (overdue.isEmpty()) {
            builder.section("*🚨 미완료 티켓 현황*\n" + INDENT + "_없음_");
            return;
        }

        // 날짜 그룹핑
        LinkedHashMap<LocalDate, List<TicketItem>> groups = new LinkedHashMap<>();
        for (TicketItem t : overdue) {
            groups.computeIfAbsent(t.getDueDate(), k -> new ArrayList<>()).add(t);
        }

        final int SECTION_LIMIT = 2800;
        StringBuilder sb = new StringBuilder("*🚨 미완료 티켓 현황*\n");

        for (Map.Entry<LocalDate, List<TicketItem>> entry : groups.entrySet()) {
            // 날짜 헤더: INDENT2 들여쓰기 + bold
            String dateLine = INDENT2 + "*" + formatDayLabel(entry.getKey()) + "*\n";
            if (sb.length() + dateLine.length() > SECTION_LIMIT) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder("*🚨 미완료 티켓 현황 (이어서)*\n");
            }
            sb.append(dateLine);

            for (TicketItem t : entry.getValue()) {
                // 이슈키·제목 모두 백틱으로 색상 강조, 이슈키는 Slack 링크도 유지
                // 주의: 백틱 내부에 <URL|text> 넣으면 링크 비활성화 → 백틱 밖에 링크 태그 유지
                String issueLink = "<" + t.getUrl() + "|`[" + t.getIssueKey() + "]`>";
                String ticketLine = INDENT3 + "• " + issueLink + " `" + t.getSummary() + "`\n";
                if (sb.length() + ticketLine.length() > SECTION_LIMIT) {
                    builder.section(sb.toString().trim());
                    sb = new StringBuilder("*🚨 미완료 티켓 현황 (이어서)*\n");
                }
                sb.append(ticketLine);
            }
        }

        builder.section(sb.toString().trim());
    }


    // =========================================================================
    // 🎫 티켓 현황 섹션 (금주/차주)
    // =========================================================================

    /**
     * 티켓 현황 섹션 추가
     *
     * <p>금요일이면 [금주] / [차주] 헤더로 분리:
     * <ul>
     *   <li>금주: dueDate &lt;= 이번 금요일 (오늘)</li>
     *   <li>차주: dueDate &gt; 이번 금요일 OR dueDate == null</li>
     * </ul>
     *
     * <p>금요일 아닌 경우: 날짜 헤더 INDENT, 티켓 행 INDENT2
     * <p>금요일인 경우: 금주/차주 헤더 INDENT, 날짜 헤더 INDENT2, 티켓 행 INDENT3
     */
    private void appendTicketSection(SlackBlockBuilder builder,
                                     List<TicketItem> tickets, LocalDate baseDate) {
        // 금주 월요일 이전 티켓(미완료 섹션)은 제외, 금주 월요일 이후만 표시
        LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<TicketItem> currentTickets = (tickets == null) ? List.of() : tickets.stream()
                .filter(t -> t.getDueDate() == null || !t.getDueDate().isBefore(thisMonday))
                .sorted(Comparator
                        .comparing((TicketItem t) -> t.getDueDate() == null
                                ? LocalDate.MAX : t.getDueDate())
                        .thenComparing(TicketItem::getIssueKey))
                .collect(Collectors.toList());

        if (currentTickets.isEmpty()) {
            builder.section("*🎫 티켓 현황*\n" + INDENT + "_활성 티켓 없음_");
            return;
        }

        boolean isFriday = DateTimeUtil.isFriday(baseDate);

        // 날짜 기준 그룹핑 (순서 보장)
        LinkedHashMap<LocalDate, List<TicketItem>> groups = new LinkedHashMap<>();
        for (TicketItem t : currentTickets) {
            groups.computeIfAbsent(t.getDueDate(), k -> new ArrayList<>()).add(t);
        }

        final int SECTION_LIMIT = 2800;
        StringBuilder sb = new StringBuilder("*🎫 티켓 현황*\n");

        if (!isFriday) {
            // 금요일 아닌 경우: 기존 들여쓰기 그대로
            sb = flushTicketGroups(builder, sb, groups, baseDate, SECTION_LIMIT, INDENT, INDENT2);
        } else {
            // 금요일: 금주/차주 분리
            // 한 주 = 월~일 이므로 금주 끝은 이번 주 일요일
            LocalDate thisWeekEnd = ReportWeekCode.thisWeekSunday(baseDate);

            LinkedHashMap<LocalDate, List<TicketItem>> thisWeekGroups = new LinkedHashMap<>();
            LinkedHashMap<LocalDate, List<TicketItem>> nextWeekGroups = new LinkedHashMap<>();

            for (Map.Entry<LocalDate, List<TicketItem>> entry : groups.entrySet()) {
                LocalDate due = entry.getKey();
                if (due != null && !due.isAfter(thisWeekEnd)) {
                    thisWeekGroups.put(due, entry.getValue());
                } else {
                    nextWeekGroups.put(due, entry.getValue());
                }
            }

            // 금주 헤더 → 날짜 헤더 INDENT2, 티켓 행 INDENT3
            sb.append(INDENT).append("*[ 금주 ]*\n");
            sb = flushTicketGroups(builder, sb, thisWeekGroups, baseDate, SECTION_LIMIT, INDENT2, INDENT3);

            sb.append("\n");

            // 차주 헤더
            String nextHdr = INDENT + "*[ 차주 ]*\n";
            if (sb.length() + nextHdr.length() > SECTION_LIMIT) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder("*🎫 티켓 현황 (이어서)*\n");
            }
            sb.append(nextHdr);
            sb = flushTicketGroups(builder, sb, nextWeekGroups, baseDate, SECTION_LIMIT, INDENT2, INDENT3);
        }

        if (sb.length() > 0) {
            builder.section(sb.toString().trim());
        }
    }

    /**
     * 날짜 그룹 맵을 순서대로 StringBuilder에 추가한다.
     * 2800자 초과 시 자동으로 section 분할.
     *
     * @param dateIndent   날짜 헤더(MM/DD) 앞 들여쓰기
     * @param ticketIndent 티켓 행 앞 들여쓰기
     * @return 현재 진행 중인 StringBuilder (분할 후 새 인스턴스일 수 있음)
     */
    private StringBuilder flushTicketGroups(SlackBlockBuilder builder,
                                            StringBuilder sb,
                                            LinkedHashMap<LocalDate, List<TicketItem>> groups,
                                            LocalDate baseDate,
                                            int sectionLimit,
                                            String dateIndent,
                                            String ticketIndent) {
        for (Map.Entry<LocalDate, List<TicketItem>> entry : groups.entrySet()) {
            LocalDate due = entry.getKey();
            String dateHdr = (due == null) ? "*기한없음*" : "*" + formatDayLabel(due) + "*";

            String hdrLine = dateIndent + dateHdr + "\n";
            if (sb.length() + hdrLine.length() > sectionLimit) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder("*🎫 티켓 현황 (이어서)*\n");
            }
            sb.append(hdrLine);

            for (TicketItem t : entry.getValue()) {
                String line = formatTicketLine(t, baseDate, ticketIndent) + "\n";
                if (sb.length() + line.length() > sectionLimit) {
                    builder.section(sb.toString().trim());
                    sb = new StringBuilder("*🎫 티켓 현황 (이어서)*\n");
                }
                sb.append(line);
            }
        }
        return sb;
    }

    /**
     * 티켓 한 줄 포맷 — ticketIndent + 기호 + 링크 + 제목(전체)
     *
     * <p><b>표시 규칙 (우선순위 순):</b>
     * <ol>
     *   <li><b>기한 초과 + 미완료</b> ({@code dueDate < baseDate AND status != DONE}):
     *       이슈키·제목 모두 인라인 백틱({@code `...`})으로 감싸 회색 강조.
     *       이슈키는 Slack 링크도 유지 ({@code <url|`[KEY]`>}). bold 처리하지 않음.</li>
     *   <li><b>당일 마감 + 미완료</b> ({@code dueDate == baseDate AND status != DONE}):
     *       이슈키·제목 모두 bold({@code *...*})로 강조.
     *       백틱과 동일한 범위(이슈키 포함 전체 행)에 적용.</li>
     *   <li><b>일반</b>: 링크 + 제목 plain 출력.</li>
     * </ol>
     *
     * @param t        티켓 아이템
     * @param baseDate 보고서 기준일 (KST 오늘)
     * @param indent   행 앞 들여쓰기 (금주/차주 없으면 INDENT2, 있으면 INDENT3)
     */
    private String formatTicketLine(TicketItem t, LocalDate baseDate, String indent) {
        String link = "<" + t.getUrl() + "|[" + t.getIssueKey() + "]>";
        boolean isDone = t.getStatus() == JiraStatusCode.DONE;

        // 당일 마감: 완료 여부와 무관하게 볼드
        boolean isDueToday = t.getDueDate() != null
                && t.getDueDate().isEqual(baseDate);

        // 기한 초과 + 미완료: 백틱 (DONE이면 이미 처리 완료이므로 백틱 대상 아님)
        boolean isOverdue = !isDone
                && t.getDueDate() != null
                && t.getDueDate().isBefore(baseDate);

        if (isDueToday) {
            // 당일 마감: 완료 여부 무관 볼드
            return indent + "• *" + link + " " + t.getSummary() + "*";
        } else if (isOverdue) {
            // 기한 초과 + 미완료: 이슈키는 백틱 링크, 제목도 백틱으로 회색 강조
            // 백틱 내부에 <url|text>를 넣으면 링크 비활성 → 링크 태그는 밖에, 백틱은 텍스트만 감쌈
            String issueLink = "<" + t.getUrl() + "|`[" + t.getIssueKey() + "]`>";
            return indent + "• " + issueLink + " `" + t.getSummary() + "`";
        } else {
            // 일반 (미래 기한, 기한 없음, 또는 완료): plain 출력
            return indent + "• " + link + " " + t.getSummary();
        }
    }


    // =========================================================================
    // 👥 팀원 티켓 현황 섹션 (Manager 전용)
    // =========================================================================

    /**
     * 팀원 티켓 현황 — Manager 보고서 전용.
     *
     * <p>형식 (금요일 아닌 경우):
     * <pre>
     * 🗂 팀원 티켓 현황
     * 팀원이름
     * • [CCE-123] 제목
     * • [CCE-456] 제목
     * 팀원이름2
     * • ...
     * </pre>
     *
     * <p>금요일이면 [금주] / [차주] 헤더 하위에 팀원이름 → 티켓 나열.
     * 팀원이름은 볼드 처리 안함. 티켓 행은 들여쓰기 없이 • 기호만.
     */
    private void appendTeamTicketSection(SlackBlockBuilder builder,
                                         java.util.LinkedHashMap<String, List<TicketItem>> teamTickets,
                                         LocalDate baseDate) {
        // ── Slack section 블록 사이에는 자동 여백이 생기므로,
        //    [금주]/[차주] + 전체 팀원을 하나의 section에 담아 여백을 최소화한다.
        //    2800자 초과 시 자동 분할하되 제목 없이 이어서 출력.
        final int LIMIT = 2800;
        LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate thisWeekSunday = ReportWeekCode.thisWeekSunday(baseDate);
        boolean isFriday = DateTimeUtil.isFriday(baseDate);

        StringBuilder sb = new StringBuilder("*🎫 팀원 티켓 현황*\n");

        if (!isFriday) {
            // 금요일 아닌 경우: 헤더 + 전체 팀원을 하나의 section에
            for (Map.Entry<String, List<TicketItem>> entry : teamTickets.entrySet()) {
                String memberName = entry.getKey();
                List<TicketItem> current = filterCurrent(entry.getValue(), thisMonday);

                sb.append(INDENT).append("*").append(memberName).append("*\n");
                if (current.isEmpty()) {
                    sb.append(INDENT2).append("• _없음_\n");
                } else {
                    for (TicketItem t : current) {
                        String line = formatTeamTicketLine(t, baseDate, INDENT2) + "\n";
                        if (sb.length() + line.length() > LIMIT) {
                            builder.section(sb.toString().trim());
                            sb = new StringBuilder();
                        }
                        sb.append(line);
                    }
                }
            }
        } else {
            // 금요일: [금주] + 전체 팀원을 하나의 section에
            sb.append(INDENT).append("*[ 금주 ]*\n");
            for (Map.Entry<String, List<TicketItem>> entry : teamTickets.entrySet()) {
                String memberName = entry.getKey();
                List<TicketItem> thisWeek = filterByRange(entry.getValue(), thisMonday, thisWeekSunday);

                sb.append(INDENT2).append("*").append(memberName).append("*\n");
                if (thisWeek.isEmpty()) {
                    sb.append(INDENT3).append("• _없음_\n");
                } else {
                    for (TicketItem t : thisWeek) {
                        String line = formatTeamTicketLine(t, baseDate, INDENT3) + "\n";
                        if (sb.length() + line.length() > LIMIT) {
                            builder.section(sb.toString().trim());
                            sb = new StringBuilder();
                        }
                        sb.append(line);
                    }
                }
            }

            // [차주] + 전체 팀원을 하나의 section에
            LocalDate nextWeekMonday = thisWeekSunday.plusDays(1);
            LocalDate nextWeekSunday = thisWeekSunday.plusWeeks(1);
            if (sb.length() > 0) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder();
            }
            sb.append(INDENT).append("*[ 차주 ]*\n");
            for (Map.Entry<String, List<TicketItem>> entry : teamTickets.entrySet()) {
                String memberName = entry.getKey();
                List<TicketItem> nextWeek = filterByRange(entry.getValue(), nextWeekMonday, nextWeekSunday);

                sb.append(INDENT2).append("*").append(memberName).append("*\n");
                if (nextWeek.isEmpty()) {
                    sb.append(INDENT3).append("• _없음_\n");
                } else {
                    for (TicketItem t : nextWeek) {
                        String line = formatTeamTicketLine(t, baseDate, INDENT3) + "\n";
                        if (sb.length() + line.length() > LIMIT) {
                            builder.section(sb.toString().trim());
                            sb = new StringBuilder();
                        }
                        sb.append(line);
                    }
                }
            }
        }

        if (sb.length() > 0) builder.section(sb.toString().trim());
    }

    /**
     * 팀원 미완료 티켓 현황 — Manager 보고서 전용.
     *
     * <p>형식:
     * <pre>
     * 🚨 팀원 미완료 티켓 현황
     * 팀원이름
     * • `[CCE-123]` `제목`
     * </pre>
     */
    private void appendTeamOverdueSection(SlackBlockBuilder builder,
                                          java.util.LinkedHashMap<String, List<TicketItem>> teamTickets,
                                          LocalDate baseDate) {
        LocalDate thisMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 헤더 + 전체 팀원을 하나의 section에 담아 Slack 자동 여백 최소화
        final int LIMIT = 2800;
        StringBuilder sb = new StringBuilder("*🚨 팀원 미완료 티켓 현황*\n");

        for (Map.Entry<String, List<TicketItem>> entry : teamTickets.entrySet()) {
            String memberName = entry.getKey();
            List<TicketItem> overdue = entry.getValue().stream()
                    .filter(t -> t.getDueDate() != null
                            && t.getDueDate().isBefore(thisMonday)
                            && t.getStatus() != JiraStatusCode.DONE)
                    .sorted(Comparator.comparing(TicketItem::getDueDate)
                            .thenComparing(TicketItem::getIssueKey))
                    .collect(Collectors.toList());

            sb.append(INDENT).append("*").append(memberName).append("*\n");
            if (overdue.isEmpty()) {
                sb.append(INDENT2).append("• _없음_\n");
            } else {
                for (TicketItem t : overdue) {
                    String issueLink = "<" + t.getUrl() + "|`[" + t.getIssueKey() + "]`>";
                    String line = INDENT2 + "• " + issueLink + " `" + t.getSummary() + "`\n";
                    if (sb.length() + line.length() > LIMIT) {
                        builder.section(sb.toString().trim());
                        sb = new StringBuilder();
                    }
                    sb.append(line);
                }
            }
        }

        if (sb.length() > 0) builder.section(sb.toString().trim());
    }

    /**
     * 팀원 티켓 현황용 한 줄 포맷 — indent + • 기호
     *
     * <pre>
     *   기한 초과 + 미완료 (dueDate < baseDate AND status != DONE): 백틱
     *   그 외 (당일, 미래, 기한없음, 완료): plain
     * </pre>
     * <p>
     * 볼드 없음 — "팀원 티켓 현황"은 담당자 본인이 아닌 관리자용 개요이므로
     * 기한 초과+미완료만 강조하고 나머지는 모두 plain으로 처리한다.
     */
    private String formatTeamTicketLine(TicketItem t, LocalDate baseDate, String indent) {
        String link = "<" + t.getUrl() + "|[" + t.getIssueKey() + "]>";

        // 기한 초과 + 미완료만 백틱
        boolean isOverdue = t.getStatus() != JiraStatusCode.DONE
                && t.getDueDate() != null
                && t.getDueDate().isBefore(baseDate);

        if (isOverdue) {
            // 기한 초과 + 미완료: 이슈키는 백틱 링크, 제목도 백틱으로 회색 강조
            String issueLink = "<" + t.getUrl() + "|`[" + t.getIssueKey() + "]`>";
            return indent + "• " + issueLink + " `" + t.getSummary() + "`";
        } else {
            // 그 외 모두 plain (완료, 당일, 미래, 기한없음)
            return indent + "• " + link + " " + t.getSummary();
        }
    }

    /**
     * 금주 월요일 이후 티켓만 필터 (미완료 섹션 제외 대상)
     */
    private List<TicketItem> filterCurrent(List<TicketItem> tickets, LocalDate thisMonday) {
        return tickets.stream()
                .filter(t -> t.getDueDate() == null || !t.getDueDate().isBefore(thisMonday))
                .sorted(Comparator.comparing((TicketItem t) ->
                                t.getDueDate() == null ? LocalDate.MAX : t.getDueDate())
                        .thenComparing(TicketItem::getIssueKey))
                .collect(Collectors.toList());
    }

    /**
     * 지정 범위(from~to 포함) + dueDate==null 인 티켓 필터
     */
    private List<TicketItem> filterByRange(List<TicketItem> tickets,
                                           LocalDate from, LocalDate to) {
        return tickets.stream()
                .filter(t -> {
                    if (t.getDueDate() == null) return false; // null은 제외 (범위 불명확)
                    return !t.getDueDate().isBefore(from) && !t.getDueDate().isAfter(to);
                })
                .sorted(Comparator.comparing(TicketItem::getDueDate)
                        .thenComparing(TicketItem::getIssueKey))
                .collect(Collectors.toList());
    }


    // =========================================================================
    // 📅 오늘 일정 섹션 [신규]
    // =========================================================================

    /**
     * 오늘 일정 섹션 추가 — /일정등록 커맨드로 등록된 당일 일정.
     *
     * <p><b>위치:</b> 팀원 미완료 티켓 현황 다음, 주요 페이지 이전.
     *
     * <p><b>표시 조건:</b>
     * {@code todaySchedules}가 null이거나 비어 있으면 섹션 자체를 미출력
     * (divider도 추가하지 않음).
     *
     * <p><b>행 포맷:</b>
     * <ul>
     *   <li>시간 지정: {@code [HH:mm-HH:mm] 제목} (예: {@code [09:00-10:00] 주간 회의})</li>
     *   <li>종일: {@code [종일] 제목}</li>
     *   <li>URL 있으면 제목 부분을 Slack 링크로 표시:
     *       {@code [HH:mm-HH:mm] <url|제목>}</li>
     * </ul>
     *
     * <p><b>정렬:</b> ScheduleCollectService에서 이미 정렬 완료 (종일 우선 → startTime 오름차순).
     *
     * @param builder        Slack 블록 빌더
     * @param todaySchedules 오늘 일정 목록 (null 또는 빈 리스트이면 미출력)
     */
    private void appendTodayScheduleSection(SlackBlockBuilder builder,
                                            List<ScheduleItem> todaySchedules) {
        builder.divider();

        if (todaySchedules == null || todaySchedules.isEmpty()) {
            builder.section("*📅 오늘 일정*\n" + INDENT + "_없음_");
            return;
        }

        final int SECTION_LIMIT = 2800;
        StringBuilder sb = new StringBuilder("*📅 오늘 일정*\n");

        for (ScheduleItem s : todaySchedules) {
            String line = INDENT + "• " + formatScheduleRow(s) + "\n";
            if (sb.length() + line.length() > SECTION_LIMIT) {
                builder.section(sb.toString().trim());
                sb = new StringBuilder("*📅 오늘 일정 (이어서)*\n");
            }
            sb.append(line);
        }

        builder.section(sb.toString().trim());
    }

    /**
     * 일정 항목 한 줄 포맷 — Slack mrkdwn
     *
     * <p>시간 태그: {@code [HH:mm-HH:mm]} 또는 {@code [종일]}
     * <p>제목: URL 있으면 {@code <url|제목>}, 없으면 plain 텍스트
     *
     * <p>종료시간 없는 경우 (파싱 실패 등): 시작시간만 표시 {@code [HH:mm]}
     *
     * @param s 일정 항목
     * @return 포맷된 Slack mrkdwn 문자열 (한 줄, 개행 없음)
     */
    private String formatScheduleRow(ScheduleItem s) {
        String timeTag = buildTimeTag(s);
        String titlePart = buildTitlePart(s.getTitle(), s.getUrl());
        return timeTag + " " + titlePart;
    }

    /**
     * 일정 항목 한 줄 포맷 — plain text (AI 후처리용)
     *
     * @param s 일정 항목
     * @return 포맷된 plain text 문자열 (한 줄, 개행 없음)
     */
    private String formatScheduleRowPlain(ScheduleItem s) {
        String timeTag = buildTimeTag(s);
        return timeTag + " " + (s.getTitle() != null ? s.getTitle() : "");
    }

    /**
     * 시간 태그 생성 — {@code [HH:mm-HH:mm]} 또는 {@code [종일]}
     */
    private String buildTimeTag(ScheduleItem s) {
        if (s.isAllDay()) {
            return "[종일]";
        }
        if (s.getStartTime() == null) {
            return "[시간미정]";
        }
        String start = formatTime(s.getStartTime());
        if (s.getEndTime() == null) {
            return "[" + start + "]";
        }
        return "[" + start + "-" + formatTime(s.getEndTime()) + "]";
    }

    /**
     * 제목 파트 생성 — URL 있으면 Slack 링크, 없으면 plain
     */
    private String buildTitlePart(String title, String url) {
        String safeTitle = (title != null) ? title : "";
        if (url != null && !url.isBlank()) {
            return "<" + url + "|" + safeTitle + ">";
        }
        return safeTitle;
    }

    /**
     * {@link LocalTime} → "HH:mm" 문자열 (항상 2자리)
     *
     * <p>요구사항: 시간은 0-23(00-02)로 표현. → {@code String.format("%02d:%02d")}
     */
    private String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }


    // =========================================================================
    // 링크 섹션
    // =========================================================================

    private void appendLinks(SlackBlockBuilder builder, List<DailyReportConfig.PageLink> links) {
        if (links == null || links.isEmpty()) return;
        builder.divider();
        StringBuilder sb = new StringBuilder("*🔗 주요 페이지*\n");
        links.forEach(l -> sb.append(INDENT).append("• <")
                .append(l.getUrl()).append("|").append(l.getTitle()).append(">\n"));
        builder.section(sb.toString().trim());
    }


    // =========================================================================
    // plain text 빌더 (AI 후처리용)
    // =========================================================================

    private String buildTicketPlainText(List<TicketItem> tickets) {
        if (tickets == null || tickets.isEmpty()) return "  활성 티켓 없음";
        StringBuilder sb = new StringBuilder();

        Map<LocalDate, List<TicketItem>> byDate = tickets.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getDueDate() == null ? LocalDate.MAX : t.getDueDate(),
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<LocalDate, List<TicketItem>> entry : byDate.entrySet()) {
            LocalDate due = entry.getKey();
            String dateHdr = due.equals(LocalDate.MAX) ? "기한없음" : formatDayLabel(due);
            sb.append("  ").append(dateHdr).append("\n");
            for (TicketItem t : entry.getValue()) {
                sb.append("    • [").append(t.getIssueKey()).append("] ")
                        .append(t.getSummary()).append("\n");
            }
        }
        return sb.toString().trim();
    }


    // =========================================================================
    // 날짜 유틸
    // =========================================================================

    /**
     * 날짜 → MM/DD(요일) 레이블 (한 자리 월/일 앞에 0 추가)
     *
     * <p>예: 2026-02-07 → "02/07(토)"
     */
    private String formatDayLabel(LocalDate date) {
        int dow = date.getDayOfWeek().getValue() - 1; // 0=월 ~ 6=일
        return String.format("%02d/%02d(%s)",
                date.getMonthValue(), date.getDayOfMonth(),
                dow < DAY_KOR_ALL.length ? DAY_KOR_ALL[dow] : "?");
    }
}
