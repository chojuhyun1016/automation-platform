package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWeekCodeTest {

    // 2025-03-10(월) ~ 2025-03-16(일)
    private static final LocalDate MONDAY = LocalDate.of(2025, 3, 10);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 3, 14);
    private static final LocalDate SATURDAY = LocalDate.of(2025, 3, 15);
    private static final LocalDate SUNDAY = LocalDate.of(2025, 3, 16);

    // ── from ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from — 금요일이면 THIS_AND_NEXT_WEEK")
    void from_friday_returnsThisAndNextWeek() {
        assertThat(ReportWeekCode.from(FRIDAY)).isEqualTo(ReportWeekCode.THIS_AND_NEXT_WEEK);
    }

    @Test
    @DisplayName("from — 금요일 외에는 THIS_WEEK")
    void from_nonFriday_returnsThisWeek() {
        assertThat(ReportWeekCode.from(MONDAY)).isEqualTo(ReportWeekCode.THIS_WEEK);
        assertThat(ReportWeekCode.from(SATURDAY)).isEqualTo(ReportWeekCode.THIS_WEEK);
        assertThat(ReportWeekCode.from(SUNDAY)).isEqualTo(ReportWeekCode.THIS_WEEK);
    }

    // ── startDate ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("startDate — 월요일이면 자기 자신 반환")
    void startDate_monday_returnsSelf() {
        assertThat(ReportWeekCode.startDate(MONDAY)).isEqualTo(MONDAY);
    }

    @Test
    @DisplayName("startDate — 금/토/일이면 해당 주 월요일 반환")
    void startDate_otherDays_returnsPreviousMonday() {
        assertThat(ReportWeekCode.startDate(FRIDAY)).isEqualTo(MONDAY);
        assertThat(ReportWeekCode.startDate(SATURDAY)).isEqualTo(MONDAY);
        assertThat(ReportWeekCode.startDate(SUNDAY)).isEqualTo(MONDAY);
    }

    // ── thisWeekSunday ──────────────────────────────────────────────────────

    @Test
    @DisplayName("thisWeekSunday — startDate + 6일")
    void thisWeekSunday_returnsStartDatePlus6() {
        assertThat(ReportWeekCode.thisWeekSunday(MONDAY)).isEqualTo(SUNDAY);
        assertThat(ReportWeekCode.thisWeekSunday(FRIDAY)).isEqualTo(SUNDAY);
    }

    // ── endDate ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("endDate — 금요일이면 차주 일요일")
    void endDate_friday_returnsNextWeekSunday() {
        LocalDate nextSunday = SUNDAY.plusWeeks(1);
        assertThat(ReportWeekCode.endDate(FRIDAY)).isEqualTo(nextSunday);
    }

    @Test
    @DisplayName("endDate — 금요일 외에는 이번 주 일요일")
    void endDate_nonFriday_returnsThisWeekSunday() {
        assertThat(ReportWeekCode.endDate(MONDAY)).isEqualTo(SUNDAY);
        assertThat(ReportWeekCode.endDate(SATURDAY)).isEqualTo(SUNDAY);
    }
}
