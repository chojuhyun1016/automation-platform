package com.riman.automation.ingest.dto.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LunchCardModalSubmitTest {

  private static String makePayloadBody(String json) {
    return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("parse — 모든 필드 정상 파싱 (private_metadata 우선)")
  void parse_allFields_parsedCorrectly() throws Exception {
    String json = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "slack_user" },
          "view": {
            "callback_id": "lunch_card_submit",
            "private_metadata": "U001|홍길동",
            "state": {
              "values": {
                "block_lunch_card_date": {
                  "action_lunch_card_date": { "selected_date": "2026-04-18" }
                },
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "apply" }]
                  }
                }
              }
            }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.getType()).isEqualTo("view_submission");
    assertThat(modal.getUserId()).isEqualTo("U001");
    assertThat(modal.getUserName()).isEqualTo("홍길동");
    assertThat(modal.getDate()).isEqualTo("2026-04-18");
    assertThat(modal.getAction()).isEqualTo("apply");
    assertThat(modal.isViewSubmission()).isTrue();
    assertThat(modal.isValidAction()).isTrue();
  }

  @Test
  @DisplayName("parse — private_metadata 없으면 username 폴백")
  void parse_noMetadata_fallbackToUsername() throws Exception {
    String json = """
        {
          "type": "view_submission",
          "user": { "id": "U002", "username": "fallback_name" },
          "view": {
            "callback_id": "lunch_card_submit",
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_date": {
                  "action_lunch_card_date": { "selected_date": "2026-04-19" }
                },
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "cancel" }]
                  }
                }
              }
            }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.getUserName()).isEqualTo("fallback_name");
    assertThat(modal.getAction()).isEqualTo("cancel");
    assertThat(modal.isValidAction()).isTrue();
  }

  @Test
  @DisplayName("isViewSubmission — block_actions 타입이면 false")
  void isViewSubmission_blockActionsType_returnsFalse() throws Exception {
    String json = """
        {
          "type": "block_actions",
          "user": { "id": "U003", "username": "user3" },
          "view": {
            "private_metadata": "",
            "state": { "values": {} }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.isViewSubmission()).isFalse();
  }

  @Test
  @DisplayName("isValidAction — 잘못된 action 값이면 false")
  void isValidAction_invalidAction_returnsFalse() throws Exception {
    String json = """
        {
          "type": "view_submission",
          "user": { "id": "U004", "username": "user4" },
          "view": {
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_date": {
                  "action_lunch_card_date": { "selected_date": "2026-04-20" }
                },
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "invalid" }]
                  }
                }
              }
            }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.isValidAction()).isFalse();
  }

  @Test
  @DisplayName("parse — 날짜/액션 누락 시 빈 문자열")
  void parse_missingFields_returnsEmptyStrings() throws Exception {
    String json = """
        {
          "type": "view_submission",
          "user": { "id": "U005", "username": "user5" },
          "view": {
            "private_metadata": "",
            "state": { "values": {} }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.getDate()).isEmpty();
    assertThat(modal.getAction()).isEmpty();
    assertThat(modal.isValidAction()).isFalse();
  }

  @Test
  @DisplayName("checkboxes 해제 — selected_options 빈 배열이면 action 빈 문자열")
  void parse_emptySelectedOptions_returnsEmptyAction() throws Exception {
    String json = """
        {
          "type": "view_submission",
          "user": { "id": "U006", "username": "user6" },
          "view": {
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_date": {
                  "action_lunch_card_date": { "selected_date": "2026-04-18" }
                },
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": []
                  }
                }
              }
            }
          }
        }
        """;

    LunchCardModalSubmit modal = LunchCardModalSubmit.parse(makePayloadBody(json));

    assertThat(modal.getAction()).isEmpty();
    assertThat(modal.isValidAction()).isFalse();
    assertThat(modal.hasAction()).isFalse();
  }

  @Test
  @DisplayName("hasDate — 날짜가 있으면 true, 빈값이면 false")
  void hasDate_returnsCorrectResult() throws Exception {
    String withDate = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
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

    String withoutDate = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
            "state": { "values": {} }
          }
        }
        """;

    assertThat(LunchCardModalSubmit.parse(makePayloadBody(withDate)).hasDate()).isTrue();
    assertThat(LunchCardModalSubmit.parse(makePayloadBody(withoutDate)).hasDate()).isFalse();
  }

  @Test
  @DisplayName("hasAction — 액션이 있으면 true, 빈값이면 false")
  void hasAction_returnsCorrectResult() throws Exception {
    String withAction = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "apply" }]
                  }
                }
              }
            }
          }
        }
        """;

    String withoutAction = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
            "state": { "values": {} }
          }
        }
        """;

    assertThat(LunchCardModalSubmit.parse(makePayloadBody(withAction)).hasAction()).isTrue();
    assertThat(LunchCardModalSubmit.parse(makePayloadBody(withoutAction)).hasAction()).isFalse();
  }

  @Test
  @DisplayName("isApply/isCancel — 액션 타입별 판별")
  void isApply_isCancel_returnsCorrectResult() throws Exception {
    String applyJson = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "apply" }]
                  }
                }
              }
            }
          }
        }
        """;

    String cancelJson = """
        {
          "type": "view_submission",
          "user": { "id": "U001", "username": "user1" },
          "view": {
            "private_metadata": "",
            "state": {
              "values": {
                "block_lunch_card_action": {
                  "action_lunch_card_action": {
                    "selected_options": [{ "value": "cancel" }]
                  }
                }
              }
            }
          }
        }
        """;

    LunchCardModalSubmit applyModal = LunchCardModalSubmit.parse(makePayloadBody(applyJson));
    assertThat(applyModal.isApply()).isTrue();
    assertThat(applyModal.isCancel()).isFalse();

    LunchCardModalSubmit cancelModal = LunchCardModalSubmit.parse(makePayloadBody(cancelJson));
    assertThat(cancelModal.isApply()).isFalse();
    assertThat(cancelModal.isCancel()).isTrue();
  }
}
