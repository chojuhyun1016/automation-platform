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

@Slf4j
public class SlackApiService {

    private final SlackClient slackClient;

    public SlackApiService() {
        this.slackClient = new SlackClient(new EnvTokenProvider("SLACK_BOT_TOKEN"));
        log.info("SlackApiService initialized");
    }

    /**
     * 공유용 SlackClient 반환.
     * SlackFacade에서 SlackClient를 다른 Facade에 주입할 때 사용.
     * HTTP 커넥션 풀 중복 생성을 방지하여 콜드스타트를 단축한다.
     */
    public SlackClient getSlackClient() {
        return slackClient;
    }

    // =========================================================================
    // 재택근무 Modal (기존 — 변경 없음)
    // =========================================================================

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

    // =========================================================================
    // 부재등록 Modal (기존 — 변경 없음)
    // =========================================================================

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

    // =========================================================================
    // 계정관리 Modal (기존 — 변경 없음)
    // =========================================================================

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
     * 현재 모달을 결과 화면으로 교체 (계정관리 views.update 직접 호출)
     *
     * <p>block_actions에서 HTTP 응답으로는 모달 변경 불가하므로
     * Slack API를 직접 호출해 결과 화면으로 교체한다.
     *
     * @param viewId  block_actions payload의 view.id
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

    // =========================================================================
    // 사용자 정보 조회 (기존 — 변경 없음)
    // =========================================================================

    /**
     * Slack users.info API로 사용자 실제 이름(real_name) 조회
     *
     * <p>한글 이름이 real_name 에 설정되어 있으면 반환, 없으면 null 반환.
     * ScheduleManageFacade 에서 모달 작성자 표시에 사용.
     *
     * @param userId Slack User ID (예: U0627755JP7)
     * @return real_name 값, 조회 실패 시 null
     */
    public String getUserRealName(String userId) {
        // get()은 BaseHttpClient protected — SlackClient 내부 메서드에 위임
        return slackClient.getUserRealName(userId);
    }

    // =========================================================================
    // 일정등록 Modal (기존 — 변경 없음)
    // =========================================================================

    /**
     * 일정등록 전용 모달 열기 — 등록된 일정 없을 때
     *
     * <p>ScheduleManageFacade.handleCommand() 에서 등록된 일정이 없을 때 호출.
     *
     * @param triggerId  Slack trigger_id
     * @param userName   Slack user_name
     * @param userId     Slack User ID
     * @param koreanName 한글 이름
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
     * 일정등록 + 삭제 통합 모달 열기 — 등록된 일정 있을 때
     *
     * <p>ScheduleManageFacade.handleCommand() 에서 등록된 일정이 있을 때 호출.
     * 상단에 삭제 드롭다운, 하단에 등록 폼이 표시된다.
     *
     * @param triggerId   Slack trigger_id
     * @param userName    Slack user_name
     * @param userId      Slack User ID
     * @param mySchedules 본인 등록 일정 목록 (삭제 드롭다운 구성용)
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
     * 일정 삭제 결과 화면으로 교체 (views.update 직접 호출)
     *
     * <p>block_actions(action_schedule_delete) 에서 HTTP 응답으로는 모달 변경 불가하므로
     * Slack API 를 직접 호출해 결과 화면으로 교체한다.
     * updateViewWithResult(계정관리) 와 동일 패턴.
     *
     * @param viewId  block_actions payload 의 view.id
     * @param success 성공 여부
     * @param message 표시할 메시지
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

    // =========================================================================
    // 현재티켓 Modal (신규 추가)
    // =========================================================================

    /**
     * /현재티켓 모달 열기 — 기간 선택(일별/주별/분기별) 드롭다운
     *
     * <p>CurrentTicketFacade.handleCommand() 에서 호출.
     * 모달 제출(view_submission) 시 CurrentTicketFacade.handleModalSubmit() 으로 분기.
     *
     * @param triggerId Slack trigger_id
     * @param userId    요청자 Slack User ID
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
