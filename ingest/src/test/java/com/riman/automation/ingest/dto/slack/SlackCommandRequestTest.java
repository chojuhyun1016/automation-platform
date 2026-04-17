package com.riman.automation.ingest.dto.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackCommandRequestTest {

  @Test
  @DisplayName("isLunchCardCommand — /점심카드 커맨드 정상 판별")
  void isLunchCardCommand_lunchCardCommand_returnsTrue() {
    String body = "command=%2F%EC%A0%90%EC%8B%AC%EC%B9%B4%EB%93%9C"
        + "&trigger_id=123&user_id=U001&user_name=testuser";

    SlackCommandRequest cmd = SlackCommandRequest.parse(body);

    assertThat(cmd.isLunchCardCommand()).isTrue();
    assertThat(cmd.getCommand()).isEqualTo("/점심카드");
  }

  @Test
  @DisplayName("isLunchCardCommand — 다른 커맨드는 false")
  void isLunchCardCommand_otherCommand_returnsFalse() {
    String body = "command=%2F%EC%9E%AC%ED%83%9D%EA%B7%BC%EB%AC%B4"
        + "&trigger_id=123&user_id=U001&user_name=testuser";

    SlackCommandRequest cmd = SlackCommandRequest.parse(body);

    assertThat(cmd.isLunchCardCommand()).isFalse();
    assertThat(cmd.isRemoteWorkCommand()).isTrue();
  }

  @Test
  @DisplayName("parse — 모든 필드 정상 파싱")
  void parse_allFields_parsedCorrectly() {
    String body = "command=%2F%EC%A0%90%EC%8B%AC%EC%B9%B4%EB%93%9C"
        + "&trigger_id=T123&user_id=U999&user_name=hong";

    SlackCommandRequest cmd = SlackCommandRequest.parse(body);

    assertThat(cmd.getCommand()).isEqualTo("/점심카드");
    assertThat(cmd.getTriggerId()).isEqualTo("T123");
    assertThat(cmd.getUserId()).isEqualTo("U999");
    assertThat(cmd.getUserName()).isEqualTo("hong");
  }

  @Test
  @DisplayName("parse — 빈 body 시 기본값 반환")
  void parse_emptyBody_returnsDefaults() {
    SlackCommandRequest cmd = SlackCommandRequest.parse("");

    assertThat(cmd.getCommand()).isEmpty();
    assertThat(cmd.isLunchCardCommand()).isFalse();
  }
}
