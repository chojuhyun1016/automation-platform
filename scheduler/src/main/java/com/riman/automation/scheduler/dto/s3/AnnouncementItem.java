package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 팀 공지 항목이다. S3 announcements.json 배열 원소와 매핑된다.
 * 노출 조건은 start_date &lt;= 오늘 &lt;= end_date이며, type 값(bold/red)은 Slack mrkdwn 강조 방식을 결정한다.
 * url이 있으면 URL 링크가 최우선이며 type은 링크 텍스트(message)에만 적용된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnnouncementItem {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

  /** 공지 내용이다. */
  private String message;

  /** 링크 URL이다(옵션). null 또는 빈 문자열이면 링크가 없다. */
  private String url;

  /** 게시 시작 날짜이다(inclusive, yyyy/MM/dd). */
  @JsonProperty("start_date")
  private String startDate;

  /** 게시 종료 날짜이다(inclusive, yyyy/MM/dd). */
  @JsonProperty("end_date")
  private String endDate;

  /**
   * 오늘 날짜가 게시 기간 안에 포함되는지 확인한다.
   *
   * @param today 기준일(KST 오늘)
   * @return start_date &lt;= today &lt;= end_date이면 true
   */
  public boolean isActive(LocalDate today) {
    try {
      LocalDate start = LocalDate.parse(startDate, FMT);
      LocalDate end = LocalDate.parse(endDate, FMT);
      return !today.isBefore(start) && !today.isAfter(end);
    } catch (DateTimeParseException | NullPointerException e) {
      return false;
    }
  }

  /**
   * 공지 강조 유형이다(옵션).
   * "bold"는 Slack mrkdwn bold로, "red"는 인라인 백틱 강조로 렌더링되며 null/빈값이면 plain 텍스트로 표시된다.
   */
  private String type;

  /**
   * URL이 존재하는지 확인한다.
   */
  public boolean hasUrl() {
    return url != null && !url.isBlank();
  }

  /**
   * bold 타입 여부를 확인한다. type이 "bold"(대소문자 무관)이면 true를 반환한다.
   */
  public boolean isBold() {
    return "bold".equalsIgnoreCase(type);
  }

  /**
   * red 타입 여부를 확인한다. Slack은 붉은색 텍스트를 직접 지원하지 않으므로
   * 포맷터에서 인라인 백틱으로 대체 표시한다. type이 "red"(대소문자 무관)이면 true를 반환한다.
   */
  public boolean isRed() {
    return "red".equalsIgnoreCase(type);
  }
}
