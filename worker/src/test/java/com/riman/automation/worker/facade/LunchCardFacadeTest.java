package com.riman.automation.worker.facade;

import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LunchCardFacadeTest {

    @Mock
    private ConfigService configService;

    @Mock
    private CalendarService calendarService;

    @Mock
    private TeamMemberService teamMemberService;

    @Mock
    private DedupeService dedupeService;

    @Mock
    private LunchCardNotificationService lunchCardNotificationService;

    private LunchCardFacade facade;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        facade = new LunchCardFacade(
                configService, calendarService, teamMemberService,
                dedupeService, lunchCardNotificationService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    // =========================================================================
    // 정상 apply 파이프라인
    // =========================================================================

    @Nested
    @DisplayName("apply — 정상 흐름")
    class ApplyTest {

        @Test
        @DisplayName("정상 apply: 이름 조회 → 중복 확인 → 캘린더 처리 → 알림 → 중복방지 저장")
        void apply_normalFlow_processesAll() {
            String json = buildLunchCardJson("evt-1", "U001", "홍길동", "apply", "2024-06-10");

            TeamMember member = createTeamMember("홍길동", "U001");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-1")).thenReturn(false);
            when(configService.getLunchCardCalendarId()).thenReturn("cal-lunch");

            facade.handle(json);

            verify(configService).getLunchCardCalendarId();
            verify(lunchCardNotificationService).sendNotification(eq("홍길동"), eq("apply"), eq("2024-06-10"));
            verify(dedupeService).saveEventKey("LUNCH_CARD#evt-1");
        }

        @Test
        @DisplayName("TeamMember 없으면 SQS name을 사용한다")
        void apply_noTeamMember_usesSqsName() {
            String json = buildLunchCardJson("evt-1", "U999", "englishName", "apply", "2024-06-10");

            when(teamMemberService.findBySlackUserId("U999")).thenReturn(null);
            when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-1")).thenReturn(false);
            when(configService.getLunchCardCalendarId()).thenReturn("cal-lunch");

            facade.handle(json);

            verify(configService).getLunchCardCalendarId();
            verify(lunchCardNotificationService).sendNotification(eq("englishName"), eq("apply"), eq("2024-06-10"));
        }
    }

    // =========================================================================
    // cancel
    // =========================================================================

    @Nested
    @DisplayName("cancel — 정상 흐름")
    class CancelTest {

        @Test
        @DisplayName("cancel: 캘린더 삭제 + 알림 발송")
        void cancel_normalFlow() {
            String json = buildLunchCardJson("evt-2", "U001", "홍길동", "cancel", "2024-06-10");

            TeamMember member = createTeamMember("홍길동", "U001");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-2")).thenReturn(false);
            when(configService.getLunchCardCalendarId()).thenReturn("cal-lunch");

            facade.handle(json);

            verify(configService).getLunchCardCalendarId();
            verify(lunchCardNotificationService).sendNotification(eq("홍길동"), eq("cancel"), eq("2024-06-10"));
            verify(dedupeService).saveEventKey("LUNCH_CARD#evt-2");
        }
    }

    // =========================================================================
    // 중복 이벤트 스킵
    // =========================================================================

    @Nested
    @DisplayName("중복 이벤트")
    class DedupeTest {

        @Test
        @DisplayName("중복 이벤트면 전체 처리를 스킵한다")
        void handle_duplicateEvent_skips() {
            String json = buildLunchCardJson("evt-dup", "U001", "홍길동", "apply", "2024-06-10");

            TeamMember member = createTeamMember("홍길동", "U001");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-dup")).thenReturn(true);

            facade.handle(json);

            verify(configService, never()).getLunchCardCalendarId();
            verify(lunchCardNotificationService, never()).sendNotification(anyString(), anyString(), anyString());
            verify(dedupeService, never()).saveEventKey(anyString());
        }
    }

    // =========================================================================
    // 유효성 검증 실패
    // =========================================================================

    @Nested
    @DisplayName("유효성 검증 — 스킵 조건")
    class ValidationTest {

        @Test
        @DisplayName("이름 없으면 스킵")
        void handle_noName_skips() {
            String json = buildLunchCardJson("evt-v1", "U001", "", "apply", "2024-06-10");

            when(teamMemberService.findBySlackUserId("U001")).thenReturn(null);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }

        @Test
        @DisplayName("날짜 없으면 스킵")
        void handle_noDate_skips() {
            String json = buildLunchCardJson("evt-v2", "U001", "홍길동", "apply", "");

            TeamMember member = createTeamMember("홍길동", "U001");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }

        @Test
        @DisplayName("알 수 없는 action이면 스킵")
        void handle_unknownAction_skips() {
            String json = buildLunchCardJson("evt-v3", "U001", "홍길동", "unknown", "2024-06-10");

            TeamMember member = createTeamMember("홍길동", "U001");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }
    }

    // =========================================================================
    // 캘린더 실패 시 DLQ 방지
    // =========================================================================

    @Test
    @DisplayName("캘린더 처리 실패해도 예외를 throw하지 않는다 (DLQ 방지)")
    void handle_calendarFailure_doesNotThrow() {
        String json = buildLunchCardJson("evt-cf", "U001", "홍길동", "apply", "2024-06-10");

        TeamMember member = createTeamMember("홍길동", "U001");
        when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
        when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-cf")).thenReturn(false);
        when(configService.getLunchCardCalendarId()).thenThrow(new RuntimeException("Calendar API down"));

        // 예외 없이 완료되어야 한다
        facade.handle(json);

        verify(dedupeService).saveEventKey("LUNCH_CARD#evt-cf");
    }

    @Test
    @DisplayName("알림 실패해도 예외를 throw하지 않는다")
    void handle_notificationFailure_doesNotThrow() {
        String json = buildLunchCardJson("evt-nf", "U001", "홍길동", "apply", "2024-06-10");

        TeamMember member = createTeamMember("홍길동", "U001");
        when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
        when(dedupeService.isDuplicateByKey("LUNCH_CARD#evt-nf")).thenReturn(false);
        when(configService.getLunchCardCalendarId()).thenReturn("cal-lunch");
        doThrow(new RuntimeException("Slack API down"))
                .when(lunchCardNotificationService).sendNotification(anyString(), anyString(), anyString());

        // 예외 없이 완료되어야 한다
        facade.handle(json);

        verify(dedupeService).saveEventKey("LUNCH_CARD#evt-nf");
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private String buildLunchCardJson(String eventId, String slackUserId, String name,
                                       String action, String date) {
        return """
                {
                  "messageType": "lunch_card",
                  "eventId": "%s",
                  "slack_user_id": "%s",
                  "name": "%s",
                  "action": "%s",
                  "date": "%s"
                }
                """.formatted(eventId, slackUserId, name, action, date);
    }

    private TeamMember createTeamMember(String name, String slackUserId) {
        TeamMember member = new TeamMember();
        member.setName(name);
        member.setSlackUserId(slackUserId);
        member.setActive(true);
        return member;
    }
}
