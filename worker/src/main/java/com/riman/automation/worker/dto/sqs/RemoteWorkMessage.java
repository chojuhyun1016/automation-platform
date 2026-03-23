package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * 재택근무 SQS 메시지 모델
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteWorkMessage {

    private String messageType;  // "remote_work"
    private String eventId;
    private Instant receivedAt;

    @JsonProperty("action")
    private String action;        // "apply" | "cancel"

    @JsonProperty("name")
    private String name;

    @JsonProperty("date")
    private String date;          // "2026-02-21"

    @JsonProperty("slack_user_id")
    private String slackUserId;
}
