package com.riman.automation.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeUtilTest {

    // ── todayKst / nowKst ───────────────────────────────────────────────────

    @Test
    @DisplayName("todayKst — null이 아닌 LocalDate 반환")
    void todayKst_returnsNonNull() {
        assertThat(DateTimeUtil.todayKst()).isNotNull();
    }

    @Test
    @DisplayName("nowKst — null이 아닌 LocalDateTime 반환")
    void nowKst_returnsNonNull() {
        assertThat(DateTimeUtil.nowKst()).isNotNull();
    }

    // ── parseDate ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseDate — 정상 yyyy-MM-dd 형식이면 LocalDate 반환")
    void parseDate_validFormat_returnsLocalDate() {
        assertThat(DateTimeUtil.parseDate("2025-03-10"))
                .isEqualTo(LocalDate.of(2025, 3, 10));
    }

    @Test
    @DisplayName("parseDate — 윤년 2/29 파싱 성공")
    void parseDate_leapYear_succeeds() {
        assertThat(DateTimeUtil.parseDate("2024-02-29"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "not-a-date", "2025/03/10", "abc-de-fg"})
    @DisplayName("parseDate — null/공백/잘못된 형식이면 null 반환")
    void parseDate_invalidInput_returnsNull(String input) {
        assertThat(DateTimeUtil.parseDate(input)).isNull();
    }

    // ── formatDate ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("formatDate — null이면 빈 문자열")
    void formatDate_null_returnsEmpty() {
        assertThat(DateTimeUtil.formatDate(null)).isEmpty();
    }

    @Test
    @DisplayName("formatDate — LocalDate를 yyyy-MM-dd 형식으로 변환")
    void formatDate_validDate_returnsFormatted() {
        assertThat(DateTimeUtil.formatDate(LocalDate.of(2025, 1, 5)))
                .isEqualTo("2025-01-05");
    }

    // ── formatDisplay ───────────────────────────────────────────────────────

    @Test
    @DisplayName("formatDisplay — null이면 빈 문자열")
    void formatDisplay_null_returnsEmpty() {
        assertThat(DateTimeUtil.formatDisplay(null)).isEmpty();
    }

    @Test
    @DisplayName("formatDisplay — M/d(E) 형식 (한국어 요일)")
    void formatDisplay_validDate_returnsKoreanFormat() {
        // 2025-03-10은 월요일
        String result = DateTimeUtil.formatDisplay(LocalDate.of(2025, 3, 10));
        assertThat(result).startsWith("3/10(");
        assertThat(result).contains("월");
    }

    // ── formatDateTime ──────────────────────────────────────────────────────

    @Test
    @DisplayName("formatDateTime — null이면 빈 문자열")
    void formatDateTime_null_returnsEmpty() {
        assertThat(DateTimeUtil.formatDateTime(null)).isEmpty();
    }

    @Test
    @DisplayName("formatDateTime — yyyy-MM-dd HH:mm 형식")
    void formatDateTime_validDateTime_returnsFormatted() {
        assertThat(DateTimeUtil.formatDateTime(LocalDateTime.of(2025, 3, 10, 14, 30)))
                .isEqualTo("2025-03-10 14:30");
    }

    // ── thisMonday ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("thisMonday — 월요일이면 자기 자신")
    void thisMonday_monday_returnsSelf() {
        LocalDate monday = LocalDate.of(2025, 3, 10);
        assertThat(DateTimeUtil.thisMonday(monday)).isEqualTo(monday);
    }

    @Test
    @DisplayName("thisMonday — 금요일이면 해당 주 월요일")
    void thisMonday_friday_returnsPreviousMonday() {
        LocalDate friday = LocalDate.of(2025, 3, 14);
        assertThat(DateTimeUtil.thisMonday(friday)).isEqualTo(LocalDate.of(2025, 3, 10));
    }

    // ── thisFriday / nextFriday ─────────────────────────────────────────────

    @Test
    @DisplayName("thisFriday — 월요일이면 해당 주 금요일")
    void thisFriday_monday_returnsFriday() {
        LocalDate monday = LocalDate.of(2025, 3, 10);
        assertThat(DateTimeUtil.thisFriday(monday)).isEqualTo(LocalDate.of(2025, 3, 14));
    }

    @Test
    @DisplayName("thisFriday — 금요일이면 자기 자신")
    void thisFriday_friday_returnsSelf() {
        LocalDate friday = LocalDate.of(2025, 3, 14);
        assertThat(DateTimeUtil.thisFriday(friday)).isEqualTo(friday);
    }

    @Test
    @DisplayName("nextFriday — thisFriday + 1주")
    void nextFriday_returnsOneWeekAfterThisFriday() {
        LocalDate monday = LocalDate.of(2025, 3, 10);
        assertThat(DateTimeUtil.nextFriday(monday)).isEqualTo(LocalDate.of(2025, 3, 21));
    }

    // ── toRfc3339Utc ────────────────────────────────────────────────────────

    @Test
    @DisplayName("toRfc3339Utc — KST 00:00을 UTC로 변환 (9시간 차이)")
    void toRfc3339Utc_convertsToUtc() {
        // KST 2025-03-10 00:00 = UTC 2025-03-09 15:00
        String result = DateTimeUtil.toRfc3339Utc(LocalDate.of(2025, 3, 10));
        assertThat(result).isEqualTo("2025-03-09T15:00:00Z");
    }

    // ── isFriday ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isFriday — 금요일이면 true, 그 외 false")
    void isFriday() {
        assertThat(DateTimeUtil.isFriday(LocalDate.of(2025, 3, 14))).isTrue();
        assertThat(DateTimeUtil.isFriday(LocalDate.of(2025, 3, 10))).isFalse();
    }
}
