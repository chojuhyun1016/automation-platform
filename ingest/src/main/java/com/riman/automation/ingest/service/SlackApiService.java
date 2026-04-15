package com.riman.automation.ingest.service;

import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.EnvTokenProvider;
import com.riman.automation.common.model.GroupwareAccountInfo;
import com.riman.automation.ingest.payload.AbsenceModalBuilder;
import com.riman.automation.ingest.payload.AccountModalBuilder;
import com.riman.automation.ingest.payload.CurrentTicketModalBuilder;
import com.riman.automation.ingest.payload.RemoteWorkModalBuilder;
import com.riman.automation.ingest.payload.ScheduleModalBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Slack API 호출을 위한 Modal 전용 서비스.
 * 각 Facade 가 공유하는 SlackClient 래퍼로, 콜드스타트 단축을 위해 SlackFacade 에서
 * 단일 인스턴스를 생성한 뒤 다른 Facade 들에 주입하여 HTTP 커넥션 풀 중복 생성을 방지한다.
 */
@Slf4j
public class SlackApiService {

  private final SlackClient slackClient;

  public SlackApiService() {
    this.slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
    log.info("SlackApiService initialized");
  }

  /**
   * 공유용 SlackClient 를 반환한다.
   * SlackFacade 에서 다른 Facade 에 SlackClient 를 주입할 때 사용한다.
   */
  public SlackClient getSlackClient() {
    return slackClient;
  }

  /**
   * /재택근무 모달을 연다.
   */
  public void openRemoteWorkModal(String triggerId, String userName, String userId) {
    try {
      String payload = RemoteWorkModalBuilder.build(triggerId, userName, userId);
      slackClient.openView(payload);
      log.info("재택 Modal 열기 성공: user={}", userName);
    } catch (Exception e) {
      log.error("재택 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("재택 Modal 열기 실패", e);
    }
  }

  /**
   * /부재등록 모달을 연다.
   */
  public void openAbsenceModal(String triggerId, String userName, String userId) {
    try {
      String payload = AbsenceModalBuilder.build(triggerId, userName, userId);
      slackClient.openView(payload);
      log.info("부재 Modal 열기 성공: user={}", userName);
    } catch (Exception e) {
      log.error("부재 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("부재 Modal 열기 실패", e);
    }
  }

  /**
   * /계정관리 변경 모달을 연다 (기존 계정 정보가 존재할 때).
   */
  public void openAccountManageModal(
      String triggerId, String userName, String userId,
      GroupwareAccountInfo existing) {
    try {
      String payload = AccountModalBuilder.buildUpdateModal(
          triggerId, userName, userId, existing);
      slackClient.openView(payload);
      log.info("계정관리 변경 Modal 열기 성공: user={}", userName);
    } catch (Exception e) {
      log.error("계정관리 변경 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("계정관리 Modal 열기 실패", e);
    }
  }

  /**
   * /계정관리 등록 모달을 연다 (기존 계정 정보가 없을 때).
   */
  public void openAccountRegisterModal(String triggerId, String userName, String userId) {
    try {
      String payload = AccountModalBuilder.buildRegisterModal(triggerId, userName, userId);
      slackClient.openView(payload);
      log.info("계정관리 등록 Modal 열기 성공: user={}", userName);
    } catch (Exception e) {
      log.error("계정관리 등록 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("계정관리 Modal 열기 실패", e);
    }
  }

  /**
   * 계정관리 block_actions 처리 후 결과 화면으로 교체한다 (views.update 직접 호출).
   * block_actions 는 HTTP 응답으로 모달을 변경할 수 없으므로 Slack API 를 직접 호출한다.
   *
   * @param viewId  block_actions payload 의 view.id
   * @param success 성공 여부
   * @param message 표시할 메시지
   */
  public void updateViewWithResult(String viewId, boolean success, String message) {
    try {
      String payload = AccountModalBuilder.buildResultView(viewId, success, message);
      slackClient.updateView(payload);
      log.info("계정관리 결과 화면 업데이트: success={}, message={}", success, message);
    } catch (Exception e) {
      log.error("계정관리 결과 화면 업데이트 실패: viewId={}", viewId, e);
      throw new RuntimeException("결과 화면 업데이트 실패", e);
    }
  }

  /**
   * Slack users.info API 로 사용자 real_name 을 조회한다.
   * ScheduleManageFacade 에서 모달 작성자 표시에 사용되며 미설정 시 null 이 반환된다.
   *
   * @param userId Slack User ID (예: U0627755JP7)
   * @return real_name 값, 조회 실패 시 null
   */
  public String getUserRealName(String userId) {
    return slackClient.getUserRealName(userId);
  }

  /**
   * 등록된 일정이 없을 때 /일정등록 전용 모달을 연다.
   */
  public void openScheduleRegisterOnlyModal(
      String triggerId, String userName, String userId, String koreanName) {
    try {
      String payload = ScheduleModalBuilder.buildRegisterOnlyModal(
          triggerId, userName, userId, koreanName);
      slackClient.openView(payload);
      log.info("일정등록 전용 Modal 열기 성공: user={}", userName);
    } catch (Exception e) {
      log.error("일정등록 전용 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("일정등록 Modal 열기 실패", e);
    }
  }

  /**
   * 등록된 일정이 있을 때 /일정등록 + 삭제 통합 모달을 연다.
   * 상단에 삭제 드롭다운, 하단에 등록 폼이 표시된다.
   */
  public void openScheduleRegisterAndDeleteModal(
      String triggerId, String userName, String userId,
      String koreanName,
      List<ScheduleMappingQueryService.MappingEntry> mySchedules) {
    try {
      String payload = ScheduleModalBuilder.buildRegisterAndDeleteModal(
          triggerId, userName, userId, koreanName, mySchedules);
      slackClient.openView(payload);
      log.info("일정등록+삭제 통합 Modal 열기 성공: user={}, scheduleCount={}",
          userName, mySchedules.size());
    } catch (Exception e) {
      log.error("일정등록+삭제 통합 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("일정등록 Modal 열기 실패", e);
    }
  }

  /**
   * 일정 삭제 결과 화면으로 교체한다 (views.update 직접 호출).
   * updateViewWithResult(계정관리)와 동일 패턴이다.
   */
  public void updateScheduleViewWithResult(String viewId, boolean success, String message) {
    try {
      String payload = ScheduleModalBuilder.buildResultView(viewId, success, message);
      slackClient.updateView(payload);
      log.info("일정 결과 화면 업데이트: success={}, message={}", success, message);
    } catch (Exception e) {
      log.error("일정 결과 화면 업데이트 실패: viewId={}", viewId, e);
      throw new RuntimeException("일정 결과 화면 업데이트 실패", e);
    }
  }

  /**
   * /현재티켓 모달을 연다 (기간 선택 드롭다운).
   * 모달 제출 시 CurrentTicketFacade.handleModalSubmit() 으로 분기된다.
   */
  public void openCurrentTicketModal(String triggerId, String userId) {
    try {
      String payload = CurrentTicketModalBuilder.build(triggerId, userId);
      slackClient.openView(payload);
      log.info("현재티켓 Modal 열기 성공: userId={}", userId);
    } catch (Exception e) {
      log.error("현재티켓 Modal 열기 실패: triggerId={}", triggerId, e);
      throw new RuntimeException("현재티켓 Modal 열기 실패", e);
    }
  }
}
