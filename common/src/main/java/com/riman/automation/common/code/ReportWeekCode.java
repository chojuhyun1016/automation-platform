package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 보고서 주차 표현 코드.
 * 한 주의 기준은 월요일~일요일이며, 금요일에만 금주+차주를 함께 표시한다.
 *
 * 예) 오늘=2/28(토) -> 금주 = 2/23(월)~3/1(일)
 * 예) 오늘=2/27(금) -> 금주 = 2/23(월)~3/1(일), 차주 = 3/2(월)~3/8(일)
 */
@Getter
@RequiredArgsConstructor
public enum ReportWeekCode {

  THIS_WEEK("금주"),
  THIS_AND_NEXT_WEEK("금주 + 차주");

  private final String displayName;

  /**
   * 헤더 표시용 주차 코드를 반환한다.
   * 금요일만 THIS_AND_NEXT_WEEK, 나머지는 THIS_WEEK.
   */
  public static ReportWeekCode from(LocalDate today) {
    return today.getDayOfWeek() == DayOfWeek.FRIDAY
        ? THIS_AND_NEXT_WEEK : THIS_WEEK;
  }

  /**
   * 보고서 수집 시작일을 반환한다. 이번 주 월요일.
   * 토/일도 해당 주(직전 월요일)로 계산된다.
   */
  public static LocalDate startDate(LocalDate today) {
    return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /**
   * 이번 주 일요일(한 주의 마지막 날)을 반환한다.
   */
  public static LocalDate thisWeekSunday(LocalDate today) {
    return startDate(today).plusDays(6);
  }

  /**
   * 보고서 수집 종료일을 반환한다.
   * 금요일이면 차주 일요일(금주+차주 전체 수집), 그 외는 이번 주 일요일.
   * 한 주가 월~일이므로 종료일은 항상 일요일이다.
   */
  public static LocalDate endDate(LocalDate today) {
    LocalDate thisSunday = thisWeekSunday(today);
    return today.getDayOfWeek() == DayOfWeek.FRIDAY
        ? thisSunday.plusWeeks(1)   // 차주 일요일
        : thisSunday;               // 이번 주 일요일
  }
}
