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

@Slf4j
public class AccountManageFacade {

    static final String SLASH_COMMAND = "/계정관리";
    static final String CALLBACK_ID = "account_manage_submit";
    static final String ACTION_DELETE_ID = "action_account_delete";

    private static final ObjectMapper OM = new ObjectMapper();

    private final GroupwareCredentialService credentialService;
    private final SlackApiService slackApiService;

    /**
     * 기존 기본 생성자 — 변경 없음
     */
    public AccountManageFacade() {
        this.credentialService = new GroupwareCredentialService();
        this.slackApiService = new SlackApiService();
    }

    /**
     * 공유 SlackApiService 주입 생성자.
     * SlackFacade에서 SlackClient 중복 생성 방지용.
     * 기존 AccountManageFacade() 생성자와 동일하게 동작하며, SlackApiService만 외부 주입.
     */
    public AccountManageFacade(SlackApiService slackApiService) {
        this.credentialService = new GroupwareCredentialService();
        this.slackApiService = slackApiService;
    }

    public APIGatewayProxyResponseEvent handleCommand(
            String triggerId, String userId, String userName) {
        try {
            log.info("계정관리 커맨드: userId={}, userName={}", userId, userName);

            GroupwareAccountInfo existing = credentialService.findBySlackUserId(userId);

            // 커맨드 수신 시점에 slackName 저장/갱신 (Slack user_name 영문 닉네임)
            // upsert 전에 호출하므로 신규/기존 모두 이름이 저장됨
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

            // slackName은 handleCommand()에서 이미 upsertSlackName()으로 저장됨
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

    public APIGatewayProxyResponseEvent handleBlockAction(String body) {
        String userId = "";
        String viewId = "";
        try {
            String decoded = URLDecoder.decode(
                    body.substring("payload=".length()), StandardCharsets.UTF_8);
            JsonNode payload = OM.readTree(decoded);

            userId = payload.path("user").path("id").asText("");
            viewId = payload.path("view").path("id").asText("");

            // 변경 모달에서 사용자가 입력한 ID/PW를 state에서 직접 읽음
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

            // block_actions는 HTTP 응답으로 모달 변경 불가 → views.update API 직접 호출 후 200만 반환
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
