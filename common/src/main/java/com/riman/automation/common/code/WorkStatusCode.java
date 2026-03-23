package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 근무 상태 코드
 *
 * <p>Google Calendar 이벤트 제목의 키워드를 감지해 분류한다.
 */
@Getter
@RequiredArgsConstructor
public enum WorkStatusCode {

    OFFICE("출근", "🏢", new String[]{}),
    REMOTE("재택", "🏠", new String[]{"재택"}),
    ANNUAL_LEAVE("연차", "🌴", new String[]{"연차"}),
    HALF_AM("오전 반차", "🌅", new String[]{"오전반차", "오전 반차"}),
    HALF_PM("오후 반차", "🌇", new String[]{"오후반차", "오후 반차"}),
    HALF_HALF_AM("오전 반반차", "🌤", new String[]{"오전반반차", "오전 반반차"}),
    HALF_HALF_PM("오후 반반차", "🌥", new String[]{"오후반반차", "오후 반반차"}),
    BUSINESS_TRIP("외근", "🚗", new String[]{"외근"}),
    UNKNOWN("알 수 없음", "❓", new String[]{});

    private final String displayName;
    private final String emoji;
    private final String[] keywords;

    /**
     * 이벤트 제목으로 근무 상태 감지. 매칭 없으면 OFFICE 반환.
     */
    public static WorkStatusCode detectFrom(String eventTitle) {
        if (eventTitle == null) return OFFICE;
        for (WorkStatusCode s : values()) {
            for (String kw : s.keywords) {
                if (eventTitle.contains(kw)) return s;
            }
        }
        return OFFICE;
    }

    public boolean isAbsent() {
        return this == ANNUAL_LEAVE || this == HALF_AM || this == HALF_PM
                || this == HALF_HALF_AM || this == HALF_HALF_PM;
    }

    public boolean isNonOffice() {
        return this != OFFICE && this != UNKNOWN;
    }
}
