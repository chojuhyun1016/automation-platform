package com.riman.automation.common.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * KST(Asia/Seoul) 기반 날짜/시간 유틸리티
 *
 * <p>Lambda는 UTC로 실행되므로, 모든 보고서 기준 날짜 계산은 반드시 이 클래스를 사용해야 한다.
 */
public final class DateTimeUtil {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 보고서 날짜 표시 형식: "2/24(화)"
     */
    public static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN);

    private DateTimeUtil() {
    }

    // ─── 현재 시각 ─────────────────────────────────────────────────────────────

    public static LocalDate todayKst() {
        return LocalDate.now(KST);
    }

    public static LocalDateTime nowKst() {
        return LocalDateTime.now(KST);
    }

    // ─── 파싱 ──────────────────────────────────────────────────────────────────

    /**
     * "yyyy-MM-dd" → LocalDate. null 또는 파싱 실패 시 null 반환
     */
    public static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 포맷 ──────────────────────────────────────────────────────────────────

    public static String formatDate(LocalDate d) {
        return d == null ? "" : d.format(DATE_FMT);
    }

    public static String formatDisplay(LocalDate d) {
        return d == null ? "" : d.format(DISPLAY_FMT);
    }

    public static String formatDateTime(LocalDateTime dt) {
        return dt == null ? "" : dt.format(DATETIME_FMT);
    }

    // ─── 주 계산 ───────────────────────────────────────────────────────────────

    public static LocalDate thisMonday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate thisFriday(LocalDate d) {
        return d.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    public static LocalDate nextFriday(LocalDate d) {
        return thisFriday(d).plusWeeks(1);
    }

    // ─── 변환 ──────────────────────────────────────────────────────────────────

    /**
     * LocalDate KST 00:00 → RFC3339 UTC 문자열 (Google Calendar API용)
     */
    public static String toRfc3339Utc(LocalDate d) {
        return d.atStartOfDay(KST).toInstant().toString();
    }

    public static boolean isFriday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.FRIDAY;
    }
}
