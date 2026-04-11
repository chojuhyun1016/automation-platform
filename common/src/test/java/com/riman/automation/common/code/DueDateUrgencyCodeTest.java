package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DueDateUrgencyCodeTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 10); // 월요일

    @Test
    @DisplayName("dueDate가 null이면 NONE")
    void of_nullDueDate_returnsNone() {
        assertThat(DueDateUrgencyCode.of(TODAY, null)).isEqualTo(DueDateUrgencyCode.NONE);
    }

    @Test
    @DisplayName("dueDate가 과거이면 OVERDUE")
    void of_pastDueDate_returnsOverdue() {
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.minusDays(1))).isEqualTo(DueDateUrgencyCode.OVERDUE);
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.minusDays(30))).isEqualTo(DueDateUrgencyCode.OVERDUE);
    }

    @Test
    @DisplayName("dueDate가 오늘이면 URGENT (daysLeft=0)")
    void of_todayDueDate_returnsUrgent() {
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY)).isEqualTo(DueDateUrgencyCode.URGENT);
    }

    @Test
    @DisplayName("dueDate가 1~3일 이내이면 URGENT")
    void of_within3Days_returnsUrgent() {
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.plusDays(1))).isEqualTo(DueDateUrgencyCode.URGENT);
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.plusDays(2))).isEqualTo(DueDateUrgencyCode.URGENT);
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.plusDays(3))).isEqualTo(DueDateUrgencyCode.URGENT);
    }

    @Test
    @DisplayName("dueDate가 4일 이상이면 NORMAL — 경계값 +3일(URGENT) vs +4일(NORMAL)")
    void of_beyond3Days_returnsNormal() {
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.plusDays(4))).isEqualTo(DueDateUrgencyCode.NORMAL);
        assertThat(DueDateUrgencyCode.of(TODAY, TODAY.plusDays(100))).isEqualTo(DueDateUrgencyCode.NORMAL);
    }
}
