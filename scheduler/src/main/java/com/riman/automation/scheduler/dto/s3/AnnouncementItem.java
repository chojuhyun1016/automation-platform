package com.riman.automation.scheduler.dto.s3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 팀 공지 항목 — S3 {@code announcements.json} 배열 원소와 매핑.
 *
 * <pre>
 * [
 *   {
 *     "message"   : "이번 주 금요일 팀 회식 예정입니다",
 *     "url"       : "https://...",     // 옵션 — 생략 가능
 *     "start_date": "2026/01/01",
 *     "end_date"  : "2026/03/31",
 *     "type"      : "bold"            // 옵션 — 생략 시 plain. "bold" 또는 "red"
 *   },
 *   {
 *     "message"   : "링크 없는 공지",
 *     "start_date": "2026/02/01",
 *     "end_date"  : "2026/02/28"
 *   }
 * ]
 * </pre>
 *
 * <p><b>노출 조건:</b> {@code start_date <= 오늘 <= end_date}
 *
 * <p><b>type 필드 표시 규칙:</b>
 * <ul>
 *   <li>{@code "bold"} — 메시지를 Slack mrkdwn bold({@code *...*})로 강조</li>
 *   <li>{@code "red"}  — 메시지를 인라인 백틱({@code `...`})으로 감싸 회색 강조
 *                        (Slack에서 붉은색 강조를 직접 지원하지 않으므로 백틱으로 대체)</li>
 *   <li>미설정(null/빈값) — plain 텍스트 (기존 동작 유지)</li>
 * </ul>
 * URL이 있으면 URL 링크가 최우선이며, type은 링크 텍스트(message)에만 적용된다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnnouncementItem {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 공지 내용
     */
    private String message;

    /**
     * 링크 URL (옵션 — null 또는 빈 문자열이면 링크 없음)
     */
    private String url;

    /**
     * 게시 시작 날짜 (inclusive), 형식: yyyy/MM/dd
     */
    @JsonProperty("start_date")
    private String startDate;

    /**
     * 게시 종료 날짜 (inclusive), 형식: yyyy/MM/dd
     */
    @JsonProperty("end_date")
    private String endDate;

    /**
     * 오늘 날짜가 게시 기간 안에 포함되는지 확인.
     *
     * @param today 기준일 (KST 오늘)
     * @return {@code start_date <= today <= end_date} 이면 true
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
     * 공지 강조 유형 (옵션)
     *
     * <ul>
     *   <li>{@code "bold"} — Slack mrkdwn bold({@code *...*}) 강조</li>
     *   <li>{@code "red"}  — 인라인 백틱({@code `...`}) 강조
     *                        (Slack 직접 붉은색 미지원으로 백틱 대체)</li>
     *   <li>{@code null} 또는 빈값 — plain 텍스트 (기본)</li>
     * </ul>
     */
    private String type;

    /**
     * URL이 존재하는지 확인
     */
    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }

    /**
     * bold 타입 여부 확인
     *
     * @return {@code type}이 {@code "bold"}(대소문자 무관)이면 true
     */
    public boolean isBold() {
        return "bold".equalsIgnoreCase(type);
    }

    /**
     * red 타입 여부 확인
     *
     * <p>Slack은 붉은색 텍스트를 직접 지원하지 않으므로,
     * 포맷터에서 인라인 백틱({@code `...`})으로 대체 표시한다.
     *
     * @return {@code type}이 {@code "red"}(대소문자 무관)이면 true
     */
    public boolean isRed() {
        return "red".equalsIgnoreCase(type);
    }
}
