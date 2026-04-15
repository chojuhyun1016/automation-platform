package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.riman.automation.common.code.AbsenceTypeCode;
import lombok.Data;

/**
 * 부재등록 SQS 메시지 DTO.
 * ingest의 SQSService.sendAbsence()가 발행하는 메시지 구조를 표현하며, AbsenceFacade가 소비한다.
 * 반차 등 단일일 유형은 AbsenceFacade에서 endDate = startDate로 보정된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbsenceMessage {

  @JsonProperty("messageType")
  private String messageType;

  @JsonProperty("eventId")
  private String eventId;

  @JsonProperty("receivedAt")
  private String receivedAt;

  @JsonProperty("slack_user_id")
  private String slackUserId;

  /** Slack username (영문). AbsenceFacade에서 TeamMemberService 조회 후 한글로 교체한다. */
  @JsonProperty("name")
  private String name;

  /** 부재 유형 레이블 (AbsenceTypeCode.getLabel() 값과 일치). */
  @JsonProperty("absenceType")
  private String absenceType;

  /** "apply" 또는 "cancel". */
  @JsonProperty("action")
  private String action;

  @JsonProperty("startDate")
  private String startDate;

  /** 날짜 1개 유형이면 AbsenceFacade에서 startDate로 덮어쓴다. */
  @JsonProperty("endDate")
  private String endDate;

  /** 공란이면 AbsenceFacade에서 "개인사유"로 설정한다. */
  @JsonProperty("reason")
  private String reason;

  public boolean isApply() {
    return "apply".equals(action);
  }

  public boolean isCancel() {
    return "cancel".equals(action);
  }

  /**
   * 날짜 1개 유형인지 여부.
   * 판정 로직은 AbsenceTypeCode enum에 위임하여 하드코딩을 피한다.
   */
  public boolean isSingleDayType() {
    AbsenceTypeCode type = AbsenceTypeCode.fromLabel(absenceType);
    return type != null && type.isSingleDayOnly();
  }

  /**
   * 실제 유효 종료일을 반환한다.
   * 날짜 1개 유형이거나 endDate가 비어있으면 startDate를 반환한다.
   */
  public String getEffectiveEndDate() {
    if (isSingleDayType()) return startDate;
    if (endDate == null || endDate.isBlank()) return startDate;
    return endDate;
  }
}
