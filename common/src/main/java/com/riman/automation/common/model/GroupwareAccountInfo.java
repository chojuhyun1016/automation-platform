package com.riman.automation.common.model;

import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 그룹웨어 계정 정보 값 객체 (불변).
 * Secrets Manager에 JSON 형태로 저장되며, groupwarePassword는 KMS 암호화(ENC: 접두사) 저장된다.
 *
 * JSON 필드: slack_user_id, slack_name, groupware_id, groupware_password, registered_at, updated_at
 *
 * 관리자 운영 가이드 (Secrets Manager 콘솔):
 *   - 목록 확인: employees 배열에서 slack_name + groupware_id로 식별
 *   - 비상 삭제: 해당 배열 항목 제거 후 저장하면 즉시 반영
 *   - 비밀번호: ENC:... 포맷으로 암호화됨, 직접 열람 불가 (정상)
 *   - 비밀번호 초기화: 해당 항목 삭제 후 사용자가 /계정관리로 재등록
 */
@Getter
@Builder
public class GroupwareAccountInfo {

  private final String slackUserId;

  /** Slack 표시 이름. 콘솔에서 누구인지 식별하기 위한 필드로, 민감 정보 아님. */
  private final String slackName;

  private final String groupwareId;

  /**
   * 그룹웨어 비밀번호. KMS 암호화 저장 (ENC: 접두사).
   * 레거시 데이터는 평문이며 다음 upsert 시 자동 암호화 마이그레이션된다.
   */
  private final String groupwarePassword;

  /** 최초 등록 일시 (KST ISO-8601). 레거시 데이터(null)는 콘솔에서 "-"로 표시된다. */
  private final String registeredAt;

  /** 최종 변경 일시 (KST ISO-8601). */
  private final String updatedAt;

  /**
   * 레거시 호환 생성자. slackName, registeredAt, updatedAt 없는 기존 데이터용.
   */
  public GroupwareAccountInfo(String slackUserId, String groupwareId, String groupwarePassword) {
    this.slackUserId = slackUserId;
    this.slackName = null;
    this.groupwareId = groupwareId;
    this.groupwarePassword = groupwarePassword;
    this.registeredAt = null;
    this.updatedAt = null;
  }

  /**
   * 전체 필드 생성자 (Builder 위임).
   */
  public GroupwareAccountInfo(
      String slackUserId, String slackName, String groupwareId,
      String groupwarePassword, String registeredAt, String updatedAt) {
    this.slackUserId = slackUserId;
    this.slackName = slackName;
    this.groupwareId = groupwareId;
    this.groupwarePassword = groupwarePassword;
    this.registeredAt = registeredAt;
    this.updatedAt = updatedAt;
  }

  /**
   * groupwareId가 유효한지 여부를 반환한다.
   */
  public boolean hasId() {
    return groupwareId != null && !groupwareId.isBlank();
  }

  /**
   * 비밀번호를 *로 마스킹한 문자열을 반환한다 (Slack Modal 표시용).
   * 길이를 고정하여 실제 길이 노출을 방지한다.
   */
  public String getMaskedPassword() {
    if (groupwarePassword == null || groupwarePassword.isEmpty()) return "";
    return "*".repeat(8);
  }

  /**
   * 현재 KST 시각을 ISO-8601 문자열로 반환한다.
   */
  public static String nowKst() {
    return ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
  }

  @Override
  public String toString() {
    return "GroupwareAccountInfo{slackUserId='" + slackUserId
        + "', slackName='" + slackName
        + "', groupwareId='" + groupwareId
        + "', password=MASKED"
        + ", registeredAt='" + registeredAt
        + "', updatedAt='" + updatedAt + "'}";
  }
}
