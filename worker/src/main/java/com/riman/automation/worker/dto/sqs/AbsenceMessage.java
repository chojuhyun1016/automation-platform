package com.riman.automation.worker.dto.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.riman.automation.common.code.AbsenceTypeCode;
import lombok.Data;

/**
 * 부재등록 SQS 메시지 DTO
 *
 * <p>ingest {@code SQSService.sendAbsence()}가 발행하는 메시지 구조.
 *
 * <pre>
 * {
 *   "messageType":   "absence",
 *   "eventId":       "uuid",
 *   "receivedAt":    "ISO instant",
 *   "slack_user_id": "U...",
 *   "name":          "영문 userName (AbsenceFacade에서 한글로 교체)",
 *   "absenceType":   "연차|오전 반차|...",
 *   "action":        "apply|cancel",
 *   "startDate":     "yyyy-MM-dd",
 *   "endDate":       "yyyy-MM-dd",
 *   "reason":        "사유 (공란이면 AbsenceFacade에서 '개인사유' 설정)"
 * }
 * </pre>
 *
 * <p>날짜 1개 유형(반차류 등)은 {@code AbsenceFacade}에서 endDate = startDate 처리.
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

    /**
     * 영문 폴백 → AbsenceFacade에서 TeamMemberService 조회 후 한글로 교체
     */
    @JsonProperty("name")
    private String name;

    /**
     * 부재 유형 레이블 (AbsenceTypeCode.getLabel()과 동일)
     */
    @JsonProperty("absenceType")
    private String absenceType;

    /**
     * "apply" | "cancel"
     */
    @JsonProperty("action")
    private String action;

    @JsonProperty("startDate")
    private String startDate;

    /**
     * 날짜 1개 유형이면 AbsenceFacade에서 startDate로 덮어씀
     */
    @JsonProperty("endDate")
    private String endDate;

    /**
     * 공란이면 AbsenceFacade에서 "개인사유" 설정
     */
    @JsonProperty("reason")
    private String reason;

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    public boolean isApply() {
        return "apply".equals(action);
    }

    public boolean isCancel() {
        return "cancel".equals(action);
    }

    /**
     * 날짜 1개 유형 여부
     * AbsenceTypeCode enum에 위임 — 하드코딩 없음.
     */
    public boolean isSingleDayType() {
        AbsenceTypeCode type = AbsenceTypeCode.fromLabel(absenceType);   // ✏️ 변경
        return type != null && type.isSingleDayOnly();
    }

    /**
     * 실제 유효 종료일 반환 (날짜 1개 유형은 startDate 반환)
     */
    public String getEffectiveEndDate() {
        if (isSingleDayType()) return startDate;
        if (endDate == null || endDate.isBlank()) return startDate;
        return endDate;
    }
}
