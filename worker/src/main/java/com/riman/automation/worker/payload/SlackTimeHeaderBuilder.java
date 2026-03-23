package com.riman.automation.worker.payload;

import com.riman.automation.common.util.DateTimeUtil;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Slack 채널용 "시간 헤더" 메시지 생성기
 *
 * <p>역할: 현재 KST 기준 채널 메시지 구분용 텍스트 1줄 생성.
 *
 * <p><b>변경 사항:</b>
 * {@code ZoneId.of("Asia/Seoul")} 직접 선언 →
 * {@link DateTimeUtil#KST} 상수 활용으로 중복 제거.
 *
 * <p>특징:
 * <ul>
 *   <li>상태 저장 없음</li>
 *   <li>캐시 없음</li>
 *   <li>SlackMessageFormatter와 완전 분리</li>
 * </ul>
 */
public final class SlackTimeHeaderBuilder {

    // DateTimeUtil.KST 활용 — ZoneId 중복 선언 제거
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd a h시", Locale.KOREAN)
                    .withZone(DateTimeUtil.KST);

    private SlackTimeHeaderBuilder() {
    }

    /**
     * 현재 KST 시각 기반 시간 헤더 문자열을 반환한다.
     *
     * <p>예: "🕔 2026-03-03 오후 3시"
     *
     * @return 시간 헤더 문자열
     */
    public static String build() {
        ZonedDateTime now = ZonedDateTime.now(DateTimeUtil.KST);
        return "🕔 " + FORMATTER.format(now);
    }
}
