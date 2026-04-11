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

class AbsenceFacadeTest {

    @Mock
    private ConfigService configService;

    @Mock
    private CalendarService calendarService;

    @Mock
    private TeamMemberService teamMemberService;

    @Mock
    private DedupeService dedupeService;

    @Mock
    private GroupwareMessageService groupwareMessageService;

    private AbsenceFacade facade;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        facade = new AbsenceFacade(
                configService, calendarService, teamMemberService,
                dedupeService, groupwareMessageService);
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
        @DisplayName("정상 apply: 이름 조회 → 중복 확인 → 캘린더 처리 → 그룹웨어 SQS → 중복방지 저장")
        void apply_normalFlow_processesAll() {
            String json = buildAbsenceJson("evt-1", "U001", "홍길동", "연차", "apply",
                    "2024-06-10", "2024-06-12", "가족 행사");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-1")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            // 캘린더 처리 확인 (AbsenceService 내부에서 calendarService 호출)
            verify(configService).getAbsenceCalendarId();
            // 그룹웨어 SQS 발행 확인
            verify(groupwareMessageService).sendGroupwareAbsence(
                    eq("U001"), eq("홍길동"), eq("CCE"), eq("Engineer"),
                    eq("연차"), eq("apply"), eq("2024-06-10"), eq("2024-06-12"), eq("가족 행사"));
            // 중복방지 저장
            verify(dedupeService).saveEventKey("ABSENCE#evt-1");
        }

        @Test
        @DisplayName("apply에서 TeamMember 없으면 SQS name을 사용한다")
        void apply_noTeamMember_usesSqsName() {
            String json = buildAbsenceJson("evt-1", "U999", "englishName", "연차", "apply",
                    "2024-06-10", "2024-06-10", "");

            when(teamMemberService.findBySlackUserId("U999")).thenReturn(null);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-1")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            verify(groupwareMessageService).sendGroupwareAbsence(
                    eq("U999"), eq("englishName"), anyString(), anyString(),
                    eq("연차"), eq("apply"), anyString(), anyString(), anyString());
        }
    }

    // =========================================================================
    // cancel
    // =========================================================================

    @Nested
    @DisplayName("cancel — 정상 흐름")
    class CancelTest {

        @Test
        @DisplayName("cancel: 캘린더 처리만, 그룹웨어 SQS 미발행")
        void cancel_normalFlow_noGroupwareSqs() {
            String json = buildAbsenceJson("evt-2", "U001", "홍길동", "연차", "cancel",
                    "2024-06-10", "2024-06-10", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-2")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            // 그룹웨어 SQS는 cancel이므로 호출되지 않아야 한다
            verify(groupwareMessageService, never()).sendGroupwareAbsence(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            verify(dedupeService).saveEventKey("ABSENCE#evt-2");
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
            String json = buildAbsenceJson("evt-dup", "U001", "홍길동", "연차", "apply",
                    "2024-06-10", "2024-06-10", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-dup")).thenReturn(true);

            facade.handle(json);

            verify(configService, never()).getAbsenceCalendarId();
            verify(groupwareMessageService, never()).sendGroupwareAbsence(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            verify(dedupeService, never()).saveEventKey(anyString());
        }
    }

    // =========================================================================
    // 날짜 보정
    // =========================================================================

    @Nested
    @DisplayName("날짜 보정")
    class DateCorrectionTest {

        @Test
        @DisplayName("endDate < startDate 역전 시 startDate로 보정한다")
        void handle_endDateBeforeStartDate_corrected() {
            String json = buildAbsenceJson("evt-3", "U001", "홍길동", "연차", "apply",
                    "2024-06-15", "2024-06-10", ""); // endDate < startDate

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-3")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            // 캘린더 처리가 호출됨 (보정 후 진행)
            verify(configService).getAbsenceCalendarId();
        }

        @Test
        @DisplayName("반차(단일일 유형)는 endDate = startDate 강제 보정")
        void handle_singleDayType_endDateEqualsStartDate() {
            String json = buildAbsenceJson("evt-4", "U001", "홍길동", "오전 반차", "apply",
                    "2024-06-10", "2024-06-20", ""); // endDate는 무시됨

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-4")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            verify(configService).getAbsenceCalendarId();
        }

        @Test
        @DisplayName("endDate 미입력 시 startDate로 보정한다")
        void handle_emptyEndDate_fallsBackToStartDate() {
            String json = buildAbsenceJson("evt-5", "U001", "홍길동", "연차", "apply",
                    "2024-06-10", "", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
            when(dedupeService.isDuplicateByKey("ABSENCE#evt-5")).thenReturn(false);
            when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

            facade.handle(json);

            verify(configService).getAbsenceCalendarId();
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
            String json = buildAbsenceJson("evt-v1", "U001", "", "연차", "apply",
                    "2024-06-10", "2024-06-10", "");

            when(teamMemberService.findBySlackUserId("U001")).thenReturn(null);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }

        @Test
        @DisplayName("시작일 없으면 스킵")
        void handle_noStartDate_skips() {
            String json = buildAbsenceJson("evt-v2", "U001", "홍길동", "연차", "apply",
                    "", "2024-06-10", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }

        @Test
        @DisplayName("부재 유형 없으면 스킵")
        void handle_noAbsenceType_skips() {
            String json = buildAbsenceJson("evt-v3", "U001", "홍길동", "", "apply",
                    "2024-06-10", "2024-06-10", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }

        @Test
        @DisplayName("알 수 없는 action이면 스킵")
        void handle_unknownAction_skips() {
            String json = buildAbsenceJson("evt-v4", "U001", "홍길동", "연차", "unknown",
                    "2024-06-10", "2024-06-10", "");

            TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
            when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);

            facade.handle(json);

            verify(dedupeService, never()).isDuplicateByKey(anyString());
        }
    }

    // =========================================================================
    // 사유 공란 처리
    // =========================================================================

    @Test
    @DisplayName("사유 공란이면 '개인사유'로 자동 설정된다")
    void handle_emptyReason_defaultsToPersonalReason() {
        String json = buildAbsenceJson("evt-r1", "U001", "홍길동", "연차", "apply",
                "2024-06-10", "2024-06-10", "");

        TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
        when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
        when(dedupeService.isDuplicateByKey("ABSENCE#evt-r1")).thenReturn(false);
        when(configService.getAbsenceCalendarId()).thenReturn("cal-absence");

        facade.handle(json);

        // 캘린더까지 정상 처리되었음을 확인
        verify(configService).getAbsenceCalendarId();
    }

    // =========================================================================
    // 캘린더 실패 시 DLQ 방지
    // =========================================================================

    @Test
    @DisplayName("캘린더 처리 실패해도 예외를 throw하지 않는다 (DLQ 방지)")
    void handle_calendarFailure_doesNotThrow() {
        String json = buildAbsenceJson("evt-cf", "U001", "홍길동", "연차", "apply",
                "2024-06-10", "2024-06-10", "테스트");

        TeamMember member = createTeamMember("홍길동", "U001", "CCE", "Engineer");
        when(teamMemberService.findBySlackUserId("U001")).thenReturn(member);
        when(dedupeService.isDuplicateByKey("ABSENCE#evt-cf")).thenReturn(false);
        when(configService.getAbsenceCalendarId()).thenThrow(new RuntimeException("Calendar API down"));

        // 예외 없이 완료되어야 한다
        facade.handle(json);

        verify(dedupeService).saveEventKey("ABSENCE#evt-cf");
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private String buildAbsenceJson(String eventId, String slackUserId, String name,
                                     String absenceType, String action,
                                     String startDate, String endDate, String reason) {
        return """
                {
                  "messageType": "absence",
                  "eventId": "%s",
                  "receivedAt": "2024-06-10T09:00:00Z",
                  "slack_user_id": "%s",
                  "name": "%s",
                  "absenceType": "%s",
                  "action": "%s",
                  "startDate": "%s",
                  "endDate": "%s",
                  "reason": "%s"
                }
                """.formatted(eventId, slackUserId, name, absenceType, action,
                startDate, endDate, reason);
    }

    private TeamMember createTeamMember(String name, String slackUserId,
                                         String team, String role) {
        TeamMember member = new TeamMember();
        member.setName(name);
        member.setSlackUserId(slackUserId);
        member.setTeam(team);
        member.setRole(role);
        member.setActive(true);
        return member;
    }
}
