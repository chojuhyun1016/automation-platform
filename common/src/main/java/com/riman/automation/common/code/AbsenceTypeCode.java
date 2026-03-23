package com.riman.automation.common.code;

import lombok.Getter;

/**
 * 부재 유형 코드
 *
 * <p>WorkStatusCode와 동일한 레이어의 도메인 분류 코드.
 * ingest(모달 빌더/파싱)와 worker(SQS 메시지 처리) 양쪽에서 공통으로 사용한다.
 *
 * <p>label: Slack payload 옵션값 + SQS 메시지 absenceType 필드값 + 캘린더 이벤트 제목 prefix.
 * singleDayOnly: true이면 날짜 1개만 사용 (AbsenceFacade에서 endDate = startDate 강제).
 *
 * <pre>
 * 날짜 1개 유형 (singleDayOnly=true):
 *   오전 반차, 오전 반반차, 오후 반차, 오후 반반차, 보건 휴가, 예비군(민방위) 훈련
 *
 * 기간 유형 (singleDayOnly=false):
 *   연차, 병가, 대체 휴가, 경조 휴가, 포상 휴가, 산전후 휴가, 휴직(관리자등록)
 * </pre>
 */
@Getter
public enum AbsenceTypeCode {

    // ── 기간 유형 ─────────────────────────────────────────────────────────────
    ANNUAL_LEAVE("연차", false),
    SICK_LEAVE("병가", false),
    COMPENSATORY_LEAVE("대체 휴가", false),
    CONDOLENCE_LEAVE("경조 휴가", false),
    AWARD_LEAVE("포상 휴가", false),
    MATERNITY_LEAVE("산전후 휴가", false),
    LEAVE_OF_ABSENCE("휴직(관리자등록)", false),

    // ── 날짜 1개 유형 ─────────────────────────────────────────────────────────
    AM_HALF("오전 반차", true),
    AM_QUARTER("오전 반반차", true),
    PM_HALF("오후 반차", true),
    PM_QUARTER("오후 반반차", true),
    HEALTH_LEAVE("보건 휴가", true),
    MILITARY_TRAINING("예비군(민방위) 훈련", true);

    private final String label;
    private final boolean singleDayOnly;

    AbsenceTypeCode(String label, boolean singleDayOnly) {
        this.label = label;
        this.singleDayOnly = singleDayOnly;
    }

    /**
     * label 문자열로 enum 조회. 매칭 없으면 null.
     */
    public static AbsenceTypeCode fromLabel(String label) {
        if (label == null) return null;
        for (AbsenceTypeCode t : values()) {
            if (t.label.equals(label)) return t;
        }
        return null;
    }

    /**
     * 유효한 label인지 검증
     */
    public static boolean isValid(String label) {
        return fromLabel(label) != null;
    }
}
