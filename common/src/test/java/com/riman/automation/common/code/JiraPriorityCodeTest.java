package com.riman.automation.common.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JiraPriorityCodeTest {

    @ParameterizedTest
    @CsvSource({
            "Highest, HIGHEST",
            "High, HIGH",
            "Medium, MEDIUM",
            "Low, LOW",
            "Lowest, LOWEST"
    })
    @DisplayName("from — 정상 이름으로 올바른 enum 반환")
    void from_validName_returnsCorrectEnum(String name, JiraPriorityCode expected) {
        assertThat(JiraPriorityCode.from(name)).isEqualTo(expected);
    }

    @Test
    @DisplayName("from — 대소문자 무관 매칭")
    void from_caseInsensitive() {
        assertThat(JiraPriorityCode.from("highest")).isEqualTo(JiraPriorityCode.HIGHEST);
        assertThat(JiraPriorityCode.from("MEDIUM")).isEqualTo(JiraPriorityCode.MEDIUM);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Critical", "없는우선순위"})
    @DisplayName("from — 매칭 없으면 UNKNOWN")
    void from_unknownName_returnsUnknown(String name) {
        assertThat(JiraPriorityCode.from(name)).isEqualTo(JiraPriorityCode.UNKNOWN);
    }

    @Test
    @DisplayName("isHighOrAbove — HIGHEST와 HIGH만 true")
    void isHighOrAbove() {
        assertThat(JiraPriorityCode.HIGHEST.isHighOrAbove()).isTrue();
        assertThat(JiraPriorityCode.HIGH.isHighOrAbove()).isTrue();
        assertThat(JiraPriorityCode.MEDIUM.isHighOrAbove()).isFalse();
        assertThat(JiraPriorityCode.LOW.isHighOrAbove()).isFalse();
        assertThat(JiraPriorityCode.LOWEST.isHighOrAbove()).isFalse();
        assertThat(JiraPriorityCode.UNKNOWN.isHighOrAbove()).isFalse();
    }

    @Test
    @DisplayName("order 값이 우선순위 순서대로 정렬된다")
    void order_isAscending() {
        assertThat(JiraPriorityCode.HIGHEST.getOrder()).isLessThan(JiraPriorityCode.HIGH.getOrder());
        assertThat(JiraPriorityCode.HIGH.getOrder()).isLessThan(JiraPriorityCode.MEDIUM.getOrder());
        assertThat(JiraPriorityCode.MEDIUM.getOrder()).isLessThan(JiraPriorityCode.LOW.getOrder());
        assertThat(JiraPriorityCode.LOW.getOrder()).isLessThan(JiraPriorityCode.LOWEST.getOrder());
        assertThat(JiraPriorityCode.LOWEST.getOrder()).isLessThan(JiraPriorityCode.UNKNOWN.getOrder());
    }
}
