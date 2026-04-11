package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AbsenceTypeCodeTest {

    @ParameterizedTest
    @CsvSource({
            "연차, ANNUAL_LEAVE",
            "병가, SICK_LEAVE",
            "대체 휴가, COMPENSATORY_LEAVE",
            "경조 휴가, CONDOLENCE_LEAVE",
            "포상 휴가, AWARD_LEAVE",
            "산전후 휴가, MATERNITY_LEAVE",
            "휴직(관리자등록), LEAVE_OF_ABSENCE",
            "오전 반차, AM_HALF",
            "오전 반반차, AM_QUARTER",
            "오후 반차, PM_HALF",
            "오후 반반차, PM_QUARTER",
            "보건 휴가, HEALTH_LEAVE",
            "예비군(민방위) 훈련, MILITARY_TRAINING"
    })
    @DisplayName("fromLabel — 정상 label로 올바른 enum 반환")
    void fromLabel_validLabel_returnsCorrectEnum(String label, AbsenceTypeCode expected) {
        assertThat(AbsenceTypeCode.fromLabel(label)).isEqualTo(expected);
    }

    @Test
    @DisplayName("fromLabel — null이면 null 반환")
    void fromLabel_null_returnsNull() {
        assertThat(AbsenceTypeCode.fromLabel(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"존재하지않는유형", "annual_leave", ""})
    @DisplayName("fromLabel — 매칭 없는 문자열이면 null 반환")
    void fromLabel_unknownLabel_returnsNull(String label) {
        assertThat(AbsenceTypeCode.fromLabel(label)).isNull();
    }

    @Test
    @DisplayName("isValid — 유효한 label이면 true")
    void isValid_validLabel_returnsTrue() {
        assertThat(AbsenceTypeCode.isValid("연차")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"없는유형"})
    @DisplayName("isValid — 무효한 label이면 false")
    void isValid_invalidLabel_returnsFalse(String label) {
        assertThat(AbsenceTypeCode.isValid(label)).isFalse();
    }

    @Test
    @DisplayName("singleDayOnly — 기간 유형은 false, 날짜 1개 유형은 true")
    void singleDayOnly_classification() {
        assertThat(AbsenceTypeCode.ANNUAL_LEAVE.isSingleDayOnly()).isFalse();
        assertThat(AbsenceTypeCode.SICK_LEAVE.isSingleDayOnly()).isFalse();
        assertThat(AbsenceTypeCode.AM_HALF.isSingleDayOnly()).isTrue();
        assertThat(AbsenceTypeCode.PM_HALF.isSingleDayOnly()).isTrue();
        assertThat(AbsenceTypeCode.HEALTH_LEAVE.isSingleDayOnly()).isTrue();
        assertThat(AbsenceTypeCode.MILITARY_TRAINING.isSingleDayOnly()).isTrue();
    }
}
