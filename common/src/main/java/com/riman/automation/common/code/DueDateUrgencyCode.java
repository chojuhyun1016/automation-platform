package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 티켓 Due Date 긴급도 코드
 *
 * <pre>
 *   기간 만료 (0일 이하) → 🔴 RED   빨강
 *   3일 이내 (1~3일)    → 🔵 BLUE  파랑
 *   그 외               → ⚫ BLACK 검정
 *   Due date 없음       → NONE
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum DueDateUrgencyCode {

    OVERDUE("기간만료", "🔴"),
    URGENT("3일이내", "🔵"),
    NORMAL("정상", "⚫"),
    NONE("없음", "⬜");

    private final String displayName;
    private final String emoji;

    /**
     * 오늘 날짜와 due date를 비교해 긴급도 반환
     *
     * @param today   기준일 (KST)
     * @param dueDate 이슈 due date (null 가능)
     */
    public static DueDateUrgencyCode of(LocalDate today, LocalDate dueDate) {
        if (dueDate == null) return NONE;
        long daysLeft = ChronoUnit.DAYS.between(today, dueDate);
        if (daysLeft < 0) return OVERDUE;
        if (daysLeft <= 3) return URGENT;
        return NORMAL;
    }
}
