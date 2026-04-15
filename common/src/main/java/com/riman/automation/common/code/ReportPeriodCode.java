package com.riman.automation.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보고서 주기 코드.
 * 현재는 DAILY만 사용하며, WEEKLY와 MONTHLY는 확장을 대비해 정의한다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportPeriodCode {

  DAILY("일일"),
  WEEKLY("주간"),
  MONTHLY("월간");

  private final String displayName;
}
