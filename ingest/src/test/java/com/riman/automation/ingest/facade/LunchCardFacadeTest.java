package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.riman.automation.ingest.dto.slack.LunchCardModalSubmit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * LunchCardFacade 테스트.
 * 검증 로직 + submit 응답(modalResult) + SQS 전송 검증.
 */
class LunchCardFacadeTest {

  private LunchCardFacade facade;

  /**
   * 생성자 호출 없이 인스턴스 생성 (WorkerMessageService/SlackApiService static 초기화 회피)
   */
  private static LunchCardFacade createBareInstance() throws Exception {
    Field f = Unsafe.class.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    Unsafe unsafe = (Unsafe) f.get(null);
    return (LunchCardFacade) unsafe.allocateInstance(LunchCardFacade.class);
  }

  @BeforeEach
  void setUp() throws Exception {
    facade = createBareInstance();
  }

  private static String makePayloadBody(String json) {
    return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
  }

  private static String makeValidSubmitJson(String date, String action) {
    return """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "testuser" },
          "view": {
            "callback_id": "lunch_card_submit",
            "private_metadata": "U001|홍길동|%s",
            "state": {
              "values": {
                "block_lunch_card_date": {
                  "action_lunch_card_date": { "selected_date": "%s" }
                }
              }
            }
          }
        }
        """.formatted(action, date);
  }

  @Nested
  @DisplayName("handleCommand")
  class HandleCommand {

    @Test
    @DisplayName("stub — 200 반환")
    void handleCommand_returnsOk() {
      APIGatewayProxyResponseEvent response =
          facade.handleCommand("T123", "U001", "testuser");

      assertThat(response.getStatusCode()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("handleModalSubmit — 검증 로직")
  class HandleModalSubmitValidation {

    @Test
    @DisplayName("날짜 미선택 — modalError 반환")
    void handleModalSubmit_missingDate_returnsModalError() {
      String json = """
          {
            "type": "view_submission",
            "user": { "id": "U001", "username": "testuser" },
            "view": {
              "callback_id": "lunch_card_submit",
              "private_metadata": "U001|testuser|apply",
              "state": {
                "values": {
                  "block_lunch_card_date": {
                    "action_lunch_card_date": {}
                  }
                }
              }
            }
          }
          """;

      APIGatewayProxyResponseEvent response = facade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("errors");
      assertThat(response.getBody()).contains("block_lunch_card_date");
    }

    @Test
    @DisplayName("잘못된 액션 — modalError 반환")
    void handleModalSubmit_invalidAction_returnsModalError() {
      String json = """
          {
            "type": "view_submission",
            "user": { "id": "U001", "username": "testuser" },
            "view": {
              "callback_id": "lunch_card_submit",
              "private_metadata": "U001|testuser|invalid",
              "state": {
                "values": {
                  "block_lunch_card_date": {
                    "action_lunch_card_date": { "selected_date": "2026-04-18" }
                  }
                }
              }
            }
          }
          """;

      APIGatewayProxyResponseEvent response = facade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("errors");
      assertThat(response.getBody()).contains("block_lunch_card_action");
    }

    @Test
    @DisplayName("view_submission 아닌 타입 — 무시하고 200 반환")
    void handleModalSubmit_notViewSubmission_returnsOk() {
      String json = """
          {
            "type": "block_actions",
            "user": { "id": "U001", "username": "testuser" },
            "view": {
              "private_metadata": "",
              "state": { "values": {} }
            }
          }
          """;

      APIGatewayProxyResponseEvent response = facade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("잘못된 페이로드 — 파싱 실패 시 실패 modalResult 반환")
    void handleModalSubmit_invalidPayload_returnsFailureModalResult() {
      APIGatewayProxyResponseEvent response = facade.handleModalSubmit("payload=invalid_json");

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("response_action");
      assertThat(response.getBody()).contains("update");
    }
  }

  @Nested
  @DisplayName("handleModalSubmit — SQS 전송 + modalResult 응답")
  class HandleModalSubmitResult {

    private LunchCardFacade spyFacade;

    @BeforeEach
    void setUpSpy() throws Exception {
      spyFacade = spy(createBareInstance());
    }

    @Test
    @DisplayName("신청 성공 — 성공 modalResult + 신청 메시지")
    void handleModalSubmit_applySuccess_returnsSuccessModalResult() {
      doReturn("msg-123").when(spyFacade).sendLunchCardToWorker(any(LunchCardModalSubmit.class));

      String json = makeValidSubmitJson("2026-04-18", "apply");
      APIGatewayProxyResponseEvent response = spyFacade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("response_action");
      assertThat(response.getBody()).contains("update");
      assertThat(response.getBody()).contains("신청이 완료되었습니다");
    }

    @Test
    @DisplayName("취소 성공 — 성공 modalResult + 취소 메시지")
    void handleModalSubmit_cancelSuccess_returnsSuccessModalResult() {
      doReturn("msg-456").when(spyFacade).sendLunchCardToWorker(any(LunchCardModalSubmit.class));

      String json = makeValidSubmitJson("2026-04-18", "cancel");
      APIGatewayProxyResponseEvent response = spyFacade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("취소가 완료되었습니다");
    }

    @Test
    @DisplayName("SQS 전송 실패 — 실패 modalResult")
    void handleModalSubmit_sqsFailure_returnsFailureModalResult() {
      doThrow(new RuntimeException("SQS connection error"))
          .when(spyFacade).sendLunchCardToWorker(any(LunchCardModalSubmit.class));

      String json = makeValidSubmitJson("2026-04-18", "apply");
      APIGatewayProxyResponseEvent response = spyFacade.handleModalSubmit(makePayloadBody(json));

      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(response.getBody()).contains("response_action");
      assertThat(response.getBody()).contains("update");
      assertThat(response.getBody()).contains("실패했습니다");
    }
  }

  @Nested
  @DisplayName("handleBlockAction")
  class HandleBlockAction {

    @Test
    @DisplayName("stub — 200 반환")
    void handleBlockAction_returnsOk() {
      APIGatewayProxyResponseEvent response = facade.handleBlockAction("payload={}");

      assertThat(response.getStatusCode()).isEqualTo(200);
    }
  }
}
