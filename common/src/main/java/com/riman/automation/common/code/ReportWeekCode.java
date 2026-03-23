package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 보고서 주차 표현 코드
 *
 * <pre>
 *   한 주의 기준: 월(月) ~ 일(日)
 *
 *   예) 오늘=2/28(토) → 금주 = 2/23(월) ~ 3/1(일)
 *   예) 오늘=2/27(금) → 금주 = 2/23(월) ~ 3/1(일)
 *                        차주 = 3/2(월)  ~ 3/8(일)
 *
 *   금요일       : 금주 + 차주 표시 (수집 endDate = 차주 일요일 3/8)
 *   월~목, 토~일 : 금주만 표시    (수집 endDate = 이번 주 일요일)
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum ReportWeekCode {

    THIS_WEEK("금주"),
    THIS_AND_NEXT_WEEK("금주 + 차주");

    private final String displayName;

    /**
     * 헤더 표시용 주차 코드.
     * 금요일만 THIS_AND_NEXT_WEEK, 나머지는 THIS_WEEK.
     */
    public static ReportWeekCode from(LocalDate today) {
        return today.getDayOfWeek() == DayOfWeek.FRIDAY
                ? THIS_AND_NEXT_WEEK : THIS_WEEK;
    }

    /**
     * 보고서 수집 시작일: 이번 주 월요일.
     * 토/일도 해당 주(직전 월요일)로 계산된다.
     */
    public static LocalDate startDate(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 이번 주 일요일 (한 주의 마지막 날).
     * startDate(월요일) + 6일.
     */
    public static LocalDate thisWeekSunday(LocalDate today) {
        return startDate(today).plusDays(6);
    }

    /**
     * 보고서 수집 종료일.
     * <ul>
     *   <li>금요일 → 차주 일요일 (금주+차주 전체 수집)</li>
     *   <li>월~목, 토, 일 → 이번 주 일요일 (금주 전체 수집)</li>
     * </ul>
     *
     * <p>한 주 = 월~일이므로 종료일은 항상 일요일이다.
     */
    public static LocalDate endDate(LocalDate today) {
        LocalDate thisSunday = thisWeekSunday(today);
        return today.getDayOfWeek() == DayOfWeek.FRIDAY
                ? thisSunday.plusWeeks(1)   // 차주 일요일
                : thisSunday;               // 이번 주 일요일
    }
}
