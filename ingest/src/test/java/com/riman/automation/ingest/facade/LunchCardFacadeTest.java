package com.riman.automation.ingest.facade;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LunchCardFacade 순수 로직 테스트.
 * 외부 의존성(SQS, Slack API)을 필요로 하지 않는 검증 로직만 테스트한다.
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
              "private_metadata": "",
              "state": {
                "values": {
                  "block_lunch_card_date": {
                    "action_lunch_card_date": {}
                  },
                  "block_lunch_card_action": {
                    "action_lunch_card_toggle": {
                      "selected_option": { "value": "apply" }
                    }
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
              "private_metadata": "",
              "state": {
                "values": {
                  "block_lunch_card_date": {
                    "action_lunch_card_date": { "selected_date": "2026-04-18" }
                  },
                  "block_lunch_card_action": {
                    "action_lunch_card_toggle": {
                      "selected_option": { "value": "invalid" }
                    }
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
    @DisplayName("잘못된 페이로드 — 파싱 실패 시 200 반환")
    void handleModalSubmit_invalidPayload_returnsOk() {
      APIGatewayProxyResponseEvent response = facade.handleModalSubmit("payload=invalid_json");

      assertThat(response.getStatusCode()).isEqualTo(200);
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
