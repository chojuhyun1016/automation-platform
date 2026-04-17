package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.riman.automation.ingest.dto.slack.LunchCardModalSubmit;
import com.riman.automation.ingest.service.SlackApiService;
import com.riman.automation.ingest.service.WorkerMessageService;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * /점심카드 커맨드 처리 Facade (stub).
 * 모달 오픈, 모달 submit (SQS 위임), block_actions 3개 진입점을 제공한다.
 * worker 측 처리 로직은 별도 Phase 에서 구현한다.
 */
@Slf4j
public class LunchCardFacade {

  static final String SLASH_COMMAND = "/점심카드";
  static final String CALLBACK_ID = "lunch_card_submit";
  static final String ACTION_DATE_ID = "action_lunch_card_date";
  static final String ACTION_TOGGLE_ID = "action_lunch_card_toggle";

  private final SlackApiService slackApiService;
  private final WorkerMessageService workerMessageService;

  /**
   * 독립 사용을 위한 기본 생성자.
   */
  public LunchCardFacade() {
    this.slackApiService = new SlackApiService();
    this.workerMessageService = WorkerMessageService.getInstance();
  }

  /**
   * 공유 SlackApiService 를 주입받는 생성자 (SlackFacade 에서 SlackClient 중복 생성을 방지하기 위함).
   */
  public LunchCardFacade(SlackApiService slackApiService) {
    this.slackApiService = slackApiService;
    this.workerMessageService = WorkerMessageService.getInstance();
  }


  /**
   * /점심카드 커맨드 수신 시 모달을 연다.
   * TODO: 모달 빌더(LunchCardModalBuilder) 및 SlackApiService.openLunchCardModal() 은 다음 Phase 에서 구현.
   */
  public APIGatewayProxyResponseEvent handleCommand(
      String triggerId, String userId, String userName) {
    log.info("점심카드 커맨드: userId={}, userName={}", userId, userName);
    // stub: 모달 오픈 로직은 다음 Phase 에서 구현
    return HttpResponse.ok("");
  }

  /**
   * 점심카드 모달 submit 처리.
   * SQS 위임 + join() 패턴으로 Lambda freeze 전에 전송을 보장한다.
   */
  public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
    LunchCardModalSubmit modal;
    try {
      modal = LunchCardModalSubmit.parse(body);
    } catch (Exception e) {
      log.warn("점심카드 모달 페이로드 파싱 실패: {}", e.getMessage());
      return HttpResponse.ok("");
    }

    if (!modal.isViewSubmission()) {
      log.info("view_submission 아님, 무시: type={}", modal.getType());
      return HttpResponse.ok("");
    }

    log.info("점심카드 submit: user={}, date={}, action={}",
        modal.getUserName(), modal.getDate(), modal.getAction());

    if (modal.getDate().isEmpty()) {
      return HttpResponse.modalError("block_lunch_card_date", "날짜를 선택해주세요.");
    }
    if (!modal.isValidAction()) {
      return HttpResponse.modalError("block_lunch_card_action", "신청 또는 취소를 선택해주세요.");
    }

    Thread sqsThread = new Thread(() -> {
      try {
        String messageId = workerMessageService.sendLunchCard(
            modal.getUserId(), modal.getUserName(),
            modal.getDate(), modal.getAction());
        log.info("점심카드 SQS 전송 완료: messageId={}, user={}, date={}, action={}",
            messageId, modal.getUserName(), modal.getDate(), modal.getAction());
      } catch (Exception e) {
        log.error("점심카드 SQS 전송 실패: user={}, date={}, action={}",
            modal.getUserName(), modal.getDate(), modal.getAction(), e);
      }
    }, "lunch-card-sqs-sender");
    sqsThread.start();

    try {
      sqsThread.join(2500);
      if (sqsThread.isAlive()) {
        log.warn("점심카드 SQS 전송 타임아웃 (2500ms): user={}", modal.getUserName());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("점심카드 SQS 전송 스레드 인터럽트: user={}", modal.getUserName());
    }

    return HttpResponse.ok("");
  }

  /**
   * 점심카드 모달의 block_actions 처리.
   * 날짜 변경(action_lunch_card_date), 토글(action_lunch_card_toggle) 등의 인터랙션을 처리한다.
   * TODO: 상세 처리 로직은 다음 Phase 에서 구현.
   */
  public APIGatewayProxyResponseEvent handleBlockAction(String body) {
    log.info("점심카드 block_action 수신");
    // stub: 상세 로직은 다음 Phase 에서 구현
    return HttpResponse.ok("");
  }
}
