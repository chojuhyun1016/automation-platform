package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Jira 이슈 우선순위 코드
 *
 * <p>order 값이 낮을수록 높은 우선순위 (정렬 기준).
 */
@Getter
@RequiredArgsConstructor
public enum JiraPriorityCode {

    HIGHEST("Highest", 1, "🔴"),
    HIGH("High", 2, "🟠"),
    MEDIUM("Medium", 3, "🟡"),
    LOW("Low", 4, "🟢"),
    LOWEST("Lowest", 5, "⚪"),
    UNKNOWN("Unknown", 99, "⚫");

    private final String displayName;
    private final int order;
    private final String emoji;

    public static JiraPriorityCode from(String name) {
        if (name == null) return UNKNOWN;
        for (JiraPriorityCode p : values()) {
            if (p.displayName.equalsIgnoreCase(name)) return p;
        }
        return UNKNOWN;
    }

    /**
     * High 이상 → 보고서 상단 배치
     */
    public boolean isHighOrAbove() {
        return this == HIGHEST || this == HIGH;
    }
}
