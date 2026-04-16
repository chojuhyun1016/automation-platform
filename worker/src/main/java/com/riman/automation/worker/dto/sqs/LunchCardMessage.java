package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 점심카드 SQS 메시지 DTO.
 * ingest 모듈이 발행하고 LunchCardFacade가 소비한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LunchCardMessage {

  private String messageType;
  private String eventId;

  /** "apply" 또는 "cancel". */
  @JsonProperty("action")
  private String action;

  @JsonProperty("name")
  private String name;

  /** 점심카드 대상 날짜 (yyyy-MM-dd). */
  @JsonProperty("date")
  private String date;

  @JsonProperty("slack_user_id")
  private String slackUserId;

  public boolean isApply() {
    return "apply".equals(action);
  }

  public boolean isCancel() {
    return "cancel".equals(action);
  }
}
