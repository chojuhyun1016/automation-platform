package com.riman.automation.groupware.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * worker → groupware SQS 메시지 VO.
 * WorkerMessageService.sendGroupwareAbsence() 가 직렬화한 JSON을 역직렬화한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupwareAbsenceMessage {

    @JsonProperty("messageType")
    private String messageType;
    @JsonProperty("eventId")
    private String eventId;
    @JsonProperty("receivedAt")
    private String receivedAt;
    @JsonProperty("slackUserId")
    private String slackUserId;
    @JsonProperty("memberName")
    private String memberName;
    @JsonProperty("team")
    private String team;
    @JsonProperty("role")
    private String role;
    /**
     * Slack /부재등록 에서 받은 부재명 그대로. 그룹웨어 select option label과 일치해야 한다.
     */
    @JsonProperty("absenceType")
    private String absenceType;
    @JsonProperty("action")
    private String action;
    @JsonProperty("startDate")
    private String startDate;
    @JsonProperty("endDate")
    private String endDate;
    @JsonProperty("reason")
    private String reason;

    public boolean isApply() {
        return "apply".equals(action);
    }

    public boolean isCancel() {
        return "cancel".equals(action);
    }
}
