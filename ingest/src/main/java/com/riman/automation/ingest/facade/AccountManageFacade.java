package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.common.exception.AutomationException;
import com.riman.automation.common.model.GroupwareAccountInfo;
import com.riman.automation.ingest.dto.slack.AccountModalSubmit;
import com.riman.automation.ingest.service.GroupwareCredentialService;
import com.riman.automation.ingest.service.SlackApiService;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * /계정관리 커맨드 처리 Facade.
 * 기존 계정 존재 여부에 따라 등록/변경 모달을 분기하며, 모달 submit 과 block_actions(삭제)를 처리한다.
 * SlackApiService 는 공유 주입 생성자를 통해 SlackFacade 로부터 재사용한다.
 */
@Slf4j
public class AccountManageFacade {

  static final String SLASH_COMMAND = "/계정관리";
  static final String CALLBACK_ID = "account_manage_submit";
  static final String ACTION_DELETE_ID = "action_account_delete";

  private static final ObjectMapper OM = new ObjectMapper();

  private final GroupwareCredentialService credentialService;
  private final SlackApiService slackApiService;

  /**
   * 독립 사용을 위한 기본 생성자.
   */
  public AccountManageFacade() {
    this.credentialService = new GroupwareCredentialService();
    this.slackApiService = new SlackApiService();
  }

  /**
   * 공유 SlackApiService 를 주입받는 생성자 (SlackFacade 에서 SlackClient 중복 생성을 방지하기 위함).
   */
  public AccountManageFacade(SlackApiService slackApiService) {
    this.credentialService = new GroupwareCredentialService();
    this.slackApiService = slackApiService;
  }

  /**
   * /계정관리 커맨드 수신 시 기존 계정 존재 여부에 따라 등록/변경 모달을 연다.
   * 커맨드 수신 시점에 Slack user_name 을 upsertSlackName() 으로 미리 저장한다.
   */
  public APIGatewayProxyResponseEvent handleCommand(
      String triggerId, String userId, String userName) {
    try {
      log.info("계정관리 커맨드: userId={}, userName={}", userId, userName);

      GroupwareAccountInfo existing = credentialService.findBySlackUserId(userId);

      // upsert 전에 호출하므로 신규/기존 모두 Slack 닉네임이 저장된다.
      credentialService.upsertSlackName(userId, userName);

      if (existing != null && existing.hasId()) {
        slackApiService.openAccountManageModal(triggerId, userName, userId, existing);
        log.info("계정관리 변경 모달 열기: userId={}, groupwareId={}",
            userId, existing.getGroupwareId());
      } else {
        slackApiService.openAccountRegisterModal(triggerId, userName, userId);
        log.info("계정관리 등록 모달 열기: userId={}", userId);
      }

      return HttpResponse.ok("");

    } catch (AutomationException e) {
      log.error("계정관리 커맨드 처리 실패 [{}]: userId={}, cause={}",
          e.getErrorCode(), userId, e.getMessage());
      return HttpResponse.internalError();
    } catch (Exception e) {
      log.error("계정관리 커맨드 처리 중 예기치 않은 오류: userId={}", userId, e);
      return HttpResponse.internalError();
    }
  }

  /**
   * 계정관리 모달 submit 처리 (register/update/delete 분기).
   * update 에서 비밀번호 미입력 시 기존 값을 유지한다.
   */
  public APIGatewayProxyResponseEvent handleModalSubmit(String body) {
    AccountModalSubmit modal;
    try {
      modal = AccountModalSubmit.parse(body);
    } catch (Exception e) {
      log.warn("계정관리 모달 페이로드 파싱 실패: {}", e.getMessage());
      return HttpResponse.badRequest("Invalid payload");
    }

    try {
      if (!modal.isViewSubmission()) {
        return HttpResponse.ok("");
      }

      log.info("계정관리 모달 submit: userId={}, action={}", modal.getUserId(), modal.getAction());

      if (!modal.hasGroupwareId()) {
        return HttpResponse.modalError("block_groupware_id", "그룹웨어 ID(사번)를 입력해 주세요.");
      }

      if (modal.isDelete() && !modal.hasGroupwarePassword()) {
        return HttpResponse.modalError("block_groupware_password", "삭제하려면 비밀번호를 입력해 주세요.");
      }

      if (modal.isRegister() && !modal.hasGroupwarePassword()) {
        return HttpResponse.modalError("block_groupware_password", "비밀번호를 입력해 주세요.");
      }

      if (modal.isDelete()) {
        return handleDelete(modal);
      }

      // update 시 비밀번호가 비어있으면 기존 값을 그대로 재사용한다.
      String passwordToUse = modal.getGroupwarePassword();
      if (!modal.isRegister() && !modal.hasGroupwarePassword()) {
        GroupwareAccountInfo existing = credentialService.findBySlackUserId(modal.getUserId());
        if (existing != null) {
          passwordToUse = existing.getGroupwarePassword();
          log.info("계정관리 update: 비밀번호 미입력, 기존 값 유지: userId={}", modal.getUserId());
        } else {
          return HttpResponse.modalError("block_groupware_password", "비밀번호를 입력해 주세요.");
        }
      }

      // slackName 은 handleCommand() 에서 이미 저장되었으므로 여기서는 전달하지 않는다.
      credentialService.upsert(
          modal.getUserId(),
          null,
          modal.getGroupwareId(),
          passwordToUse
      );

      String successMsg = modal.isRegister()
          ? "계정이 등록되었습니다."
          : "계정이 변경되었습니다.";
      log.info("계정관리 {} 완료: userId={}", modal.getAction(), modal.getUserId());
      return HttpResponse.modalResult(true, successMsg, "계정관리");

    } catch (AutomationException e) {
      log.error("계정관리 모달 submit 처리 실패 [{}]: userId={}, cause={}",
          e.getErrorCode(), modal.getUserId(), e.getMessage());
      return HttpResponse.modalResult(false, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "계정관리");
    } catch (Exception e) {
      log.error("계정관리 모달 submit 처리 중 예기치 않은 오류: userId={}", modal.getUserId(), e);
      return HttpResponse.modalResult(false, "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "계정관리");
    }
  }

  /**
   * 변경 모달의 삭제 버튼(block_actions) 처리.
   * block_actions 는 HTTP 응답으로 모달 변경이 불가하므로 views.update 를 직접 호출한 뒤 200 만 반환한다.
   */
  public APIGatewayProxyResponseEvent handleBlockAction(String body) {
    String userId = "";
    String viewId = "";
    try {
      String decoded = URLDecoder.decode(
          body.substring("payload=".length()), StandardCharsets.UTF_8);
      JsonNode payload = OM.readTree(decoded);

      userId = payload.path("user").path("id").asText("");
      viewId = payload.path("view").path("id").asText("");

      // 사용자가 모달에 직접 입력한 ID/PW 를 state 에서 읽는다.
      JsonNode values = payload.path("view").path("state").path("values");
      String groupwareId = values
          .path("block_groupware_id").path("action_groupware_id")
          .path("value").asText("").trim();
      String groupwarePassword = values
          .path("block_groupware_password").path("action_groupware_password")
          .path("value").asText("").trim();

      log.info("계정관리 삭제 시도: userId={}, groupwareId={}", userId, groupwareId);

      if (groupwareId.isBlank() || groupwarePassword.isBlank()) {
        slackApiService.updateViewWithResult(viewId, false,
            "그룹웨어 ID와 비밀번호를 입력한 후 삭제 버튼을 눌러주세요.");
        return HttpResponse.ok("");
      }

      boolean deleted = credentialService.deleteWithVerification(
          userId, groupwareId, groupwarePassword);

      if (!deleted) {
        log.warn("계정관리 삭제 실패 (ID/비밀번호 불일치): userId={}", userId);
        slackApiService.updateViewWithResult(viewId, false,
            "ID 또는 비밀번호가 일치하지 않습니다.\n다시 확인 후 시도해 주세요.");
      } else {
        log.info("계정관리 삭제 완료: userId={}", userId);
        slackApiService.updateViewWithResult(viewId, true,
            "계정이 삭제되었습니다.");
      }

      return HttpResponse.ok("");

    } catch (AutomationException e) {
      log.error("계정관리 삭제 처리 실패 [{}]: userId={}, cause={}",
          e.getErrorCode(), userId, e.getMessage());
      if (!viewId.isBlank()) {
        slackApiService.updateViewWithResult(viewId, false,
            "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
      }
      return HttpResponse.ok("");
    } catch (Exception e) {
      log.error("계정관리 삭제 처리 중 예기치 않은 오류: userId={}", userId, e);
      if (!viewId.isBlank()) {
        slackApiService.updateViewWithResult(viewId, false,
            "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
      }
      return HttpResponse.ok("");
    }
  }

  /**
   * view_submission 모드에서의 삭제 처리 (block_actions 가 아닌 모달 submit 경로).
   */
  private APIGatewayProxyResponseEvent handleDelete(AccountModalSubmit modal) {
    boolean deleted = credentialService.deleteWithVerification(
        modal.getUserId(),
        modal.getGroupwareId(),
        modal.getGroupwarePassword()
    );

    if (!deleted) {
      log.warn("계정관리 삭제 실패 (ID/비밀번호 불일치): userId={}", modal.getUserId());
      return HttpResponse.modalResult(false,
          "ID 또는 비밀번호가 일치하지 않습니다.\n다시 확인 후 시도해 주세요.");
    }

    log.info("계정관리 삭제 완료: userId={}", modal.getUserId());
    return HttpResponse.modalResult(true, "계정이 삭제되었습니다.", "계정관리");
  }
}
