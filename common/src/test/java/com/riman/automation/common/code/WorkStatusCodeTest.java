package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkStatusCodeTest {

    @ParameterizedTest
    @CsvSource({
            "홍길동 재택, REMOTE",
            "김철수 오전반차, HALF_AM",
            "김철수 오전 반차, HALF_AM",
            "이영희 오후반차, HALF_PM",
            "이영희 오후 반차, HALF_PM",
            "박지성 오전반반차, HALF_HALF_AM",
            "박지성 오전 반반차, HALF_HALF_AM",
            "손흥민 오후반반차, HALF_HALF_PM",
            "손흥민 오후 반반차, HALF_HALF_PM",
            "홍길동 연차, ANNUAL_LEAVE",
            "김과장 외근 — 강남, BUSINESS_TRIP"
    })
    @DisplayName("detectFrom — 키워드 포함 제목에서 올바른 상태 감지")
    void detectFrom_keywordInTitle_returnsCorrectStatus(String title, WorkStatusCode expected) {
        assertThat(WorkStatusCode.detectFrom(title)).isEqualTo(expected);
    }

    @Test
    @DisplayName("detectFrom — null이면 OFFICE 반환")
    void detectFrom_null_returnsOffice() {
        assertThat(WorkStatusCode.detectFrom(null)).isEqualTo(WorkStatusCode.OFFICE);
    }

    @Test
    @DisplayName("detectFrom — 키워드 미포함이면 OFFICE 반환")
    void detectFrom_noKeyword_returnsOffice() {
        assertThat(WorkStatusCode.detectFrom("팀 회의 10시")).isEqualTo(WorkStatusCode.OFFICE);
        assertThat(WorkStatusCode.detectFrom("")).isEqualTo(WorkStatusCode.OFFICE);
    }

    @Test
    @DisplayName("isAbsent — 연차/반차 5개만 true")
    void isAbsent_classification() {
        assertThat(WorkStatusCode.ANNUAL_LEAVE.isAbsent()).isTrue();
        assertThat(WorkStatusCode.HALF_AM.isAbsent()).isTrue();
        assertThat(WorkStatusCode.HALF_PM.isAbsent()).isTrue();
        assertThat(WorkStatusCode.HALF_HALF_AM.isAbsent()).isTrue();
        assertThat(WorkStatusCode.HALF_HALF_PM.isAbsent()).isTrue();

        assertThat(WorkStatusCode.OFFICE.isAbsent()).isFalse();
        assertThat(WorkStatusCode.REMOTE.isAbsent()).isFalse();
        assertThat(WorkStatusCode.BUSINESS_TRIP.isAbsent()).isFalse();
        assertThat(WorkStatusCode.UNKNOWN.isAbsent()).isFalse();
    }

    @Test
    @DisplayName("isNonOffice — OFFICE와 UNKNOWN만 false")
    void isNonOffice_classification() {
        assertThat(WorkStatusCode.OFFICE.isNonOffice()).isFalse();
        assertThat(WorkStatusCode.UNKNOWN.isNonOffice()).isFalse();

        assertThat(WorkStatusCode.REMOTE.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.ANNUAL_LEAVE.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.HALF_AM.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.HALF_PM.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.HALF_HALF_AM.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.HALF_HALF_PM.isNonOffice()).isTrue();
        assertThat(WorkStatusCode.BUSINESS_TRIP.isNonOffice()).isTrue();
    }
}
