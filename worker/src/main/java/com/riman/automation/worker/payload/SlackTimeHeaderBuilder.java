package com.riman.automation.worker.payload;

import com.riman.automation.common.util.DateTimeUtil;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Slack 채널용 "시간 헤더" 메시지 빌더.
 * 현재 KST 기준 채널 메시지 구분용 텍스트 한 줄을 생성하며 상태와 캐시를 갖지 않는다.
 */
public final class SlackTimeHeaderBuilder {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd a h시", Locale.KOREAN)
          .withZone(DateTimeUtil.KST);

  private SlackTimeHeaderBuilder() {
  }

  /**
   * 현재 KST 시각 기반 시간 헤더 문자열을 반환한다.
   */
  public static String build() {
    ZonedDateTime now = ZonedDateTime.now(DateTimeUtil.KST);
    return "🕔 " + FORMATTER.format(now);
  }
}
