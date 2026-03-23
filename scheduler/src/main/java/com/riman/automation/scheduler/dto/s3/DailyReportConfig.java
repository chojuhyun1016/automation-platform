package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 일일 보고서 설정
 *
 * <p>S3 config.json 의 "dailyReport" 섹션과 매핑된다.
 *
 * <pre>
 * {
 *   "dailyReport": {
 *     "enabled": true,
 *     "report_channel_id": "C09DAQAABS5",
 *     "calendar_id": "abcd@group.calendar.google.com",
 *     "ticket_calendar_id": "efgh@group.calendar.google.com",
 *     "schedule_calendar_id": "ijkl@group.calendar.google.com",
 *     "jira_project_keys": ["CCE", "RBO"],
 *     "announcements": ["이번 주 금요일 팀 회식"],
 *     "links": [
 *       {"title": "팀 컨플루언스", "url": "https://..."},
 *       {"title": "주간보고",      "url": "https://..."}
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p>향후 주간/월간 보고서는 weeklyReport, monthlyReport 섹션으로 추가.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyReportConfig {

    /**
     * 보고서 활성화 여부
     */
    private Boolean enabled = true;

    /**
     * 보고서를 전송할 Slack 채널 ID.
     * "channel" 또는 "dm:{slackUserId}" 형식을 지원한다.
     * 향후 "dm:all" 등으로 개인 DM 대량 전송 확장 예정.
     */
    @JsonProperty("report_channel_id")
    private String reportChannelId;

    /**
     * 재택/부재 조회용 Google Calendar ID
     */
    @JsonProperty("calendar_id")
    private String calendarId;

    /**
     * 티켓 이벤트 조회용 Google Calendar ID
     *
     * <p>이 캘린더에서 팀원별 담당 티켓을 읽는다.
     * 이벤트 제목 형식: "[CCE-123] 제목 (담당자이름)"
     * calendar_id 와 같은 캘린더를 써도 되고, 별도 캘린더를 써도 된다.
     */
    @JsonProperty("ticket_calendar_id")
    private String ticketCalendarId;

    /**
     * /일정등록 커맨드 이벤트 조회용 Google Calendar ID
     *
     * <p>worker ScheduleFacade가 일정을 등록하는 캘린더와 동일해야 한다.
     * worker의 ConfigService.getScheduleCalendarId() 가 사용하는 캘린더 ID를 입력한다.
     *
     * <p>미설정(null)이면 오늘 일정 섹션 미출력.
     */
    @JsonProperty("schedule_calendar_id")
    private String scheduleCalendarId;

    /**
     * 보고서에 포함할 Jira 프로젝트 키 목록
     */
    @JsonProperty("jira_project_keys")
    private List<String> jiraProjectKeys;

    /**
     * 팀 공지 파일의 S3 오브젝트 키.
     *
     * <p>버킷은 configBucket 과 동일하며, 키만 별도 지정한다.
     * <p>예: {@code "automation-platform-747461205838/announcements.json"}
     * <p>미설정(null) 시 공지 없음으로 처리.
     */
    @JsonProperty("announcements_key")
    private String announcementsKey;

    /**
     * 주요 페이지 링크
     */
    private List<PageLink> links;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageLink {
        private String title;
        private String url;
    }
}
