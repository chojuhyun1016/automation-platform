package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * 재택근무 SQS 메시지 DTO.
 * ingest 모듈이 발행하고 RemoteWorkFacade가 소비한다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteWorkMessage {

  private String messageType;
  private String eventId;
  private Instant receivedAt;

  /** "apply" 또는 "cancel". */
  @JsonProperty("action")
  private String action;

  @JsonProperty("name")
  private String name;

  /** 재택 대상 날짜 (yyyy-MM-dd). */
  @JsonProperty("date")
  private String date;

  @JsonProperty("slack_user_id")
  private String slackUserId;
}
