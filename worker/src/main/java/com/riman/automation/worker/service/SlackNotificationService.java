package com.riman.automation.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riman.automation.clients.slack.SlackClient;
import com.riman.automation.common.auth.TokenProvider;
import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.worker.service.ConfigService.ProjectRouting;
import com.riman.automation.worker.dto.s3.TeamMember;
import com.riman.automation.worker.dto.jira.JiraWebhookEvent;
import com.riman.automation.worker.payload.JiraSlackMessageBuilder;
import com.riman.automation.worker.payload.SlackTimeHeaderBuilder;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Jira 웹훅 이벤트를 Slack 채널/DM으로 전송하는 알림 서비스.
 * HTTP 전송은 clients 계층의 SlackClient에 위임하며, Bot 토큰은 Secrets Manager에서 조회하여 5분 TTL 캐시에 보관한다.
 *
 * DM 수신자 결정 규칙.
 * 담당자 변경 이벤트: 팀원↔팀원은 from+to 2명, 팀원→비팀원은 from(팀원) 1명, 비팀원→팀원은 to(팀원) 1명, 비팀원↔비팀원은 없음.
 * 기타 변경 이벤트: 현재 담당자가 팀원이면 1명, 비팀원이면 없음. reporter와 이벤트 트리거 user는 수신자에서 제외한다.
 */
@Slf4j
public class SlackNotificationService {

  private static final long TOKEN_CACHE_TTL_SECONDS = 300;

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final SecretsManagerClient secretsManagerClient;
  private final TeamMemberService teamMemberService;

  /** secretName → CachedToken 캐시 (TTL: 5분). */
  private final Map<String, CachedToken> tokenCache = new HashMap<>();

  public SlackNotificationService() {
    this.secretsManagerClient = SecretsManagerClient.builder().build();
    this.teamMemberService = new TeamMemberService();
    log.info("SlackNotificationService 초기화 완료");
  }

  /**
   * 테스트용 생성자. Mock SecretsManagerClient와 TeamMemberService를 주입한다.
   */
  SlackNotificationService(SecretsManagerClient secretsManagerClient,
                           TeamMemberService teamMemberService) {
    this.secretsManagerClient = secretsManagerClient;
    this.teamMemberService = teamMemberService;
  }

  /**
   * Jira 웹훅 이벤트를 기반으로 Slack 알림을 전송한다.
   * 라우팅 설정에 따라 채널 전송과 개인 DM 전송을 수행한다.
   *
   * @param event   Jira 웹훅 이벤트
   * @param routing 프로젝트 라우팅 설정 (채널, 토큰 시크릿, 전송 옵션 포함)
   */
  public void sendNotification(JiraWebhookEvent event, ProjectRouting routing) {
    try {
      String botToken = getBotToken(routing.getSlackBotTokenSecret());
      SlackClient slackClient = buildSlackClient(botToken);

      if (Boolean.TRUE.equals(routing.getSendToChannel())) {
        sendToChannel(event, routing, slackClient);
      }

      if (Boolean.TRUE.equals(routing.getSendToIndividuals())) {
        List<TeamMember> recipients = resolveTeamMemberRecipients(event);

        if (recipients.isEmpty()) {
          log.info("DM 수신자 없음: issueKey={}", event.getIssue().getKey());
        } else {
          log.info("DM 발송: count={}, members={}, issueKey={}",
              recipients.size(),
              recipients.stream()
                  .map(TeamMember::getName)
                  .collect(Collectors.toList()),
              event.getIssue().getKey());
          sendToIndividuals(event, slackClient, recipients);
        }
      }

    } catch (ExternalApiClientException e) {
      throw e;
    } catch (Exception e) {
      log.error("Slack 알림 전송 실패: issueKey={}", event.getIssue().getKey(), e);
      throw new ExternalApiClientException("Slack",
          "알림 전송 실패: issueKey=" + event.getIssue().getKey(), e);
    }
  }

  /**
   * DM 수신자 목록을 결정한다.
   * 담당자 변경 이벤트인지 기타 변경 이벤트인지에 따라 규칙이 달라진다 (클래스 Javadoc 참조).
   */
  private List<TeamMember> resolveTeamMemberRecipients(JiraWebhookEvent event) {
    // LinkedHashSet: 순서 유지 + slackUserId 중복 방지 (같은 사람이 from/to인 경우)
    Set<String> slackUserIds = new LinkedHashSet<>();

    JiraWebhookEvent.Fields fields = event.getIssue().getFields();

    TeamMember toMember = null;
    if (fields.getAssignee() != null && fields.getAssignee().getAccountId() != null) {
      toMember = teamMemberService.findByAccountId(fields.getAssignee().getAccountId());
    }

    if (hasAssigneeChangelog(event)) {
      String fromAccountId = getAssigneeFromAccountId(event);
      TeamMember fromMember = null;
      if (fromAccountId != null) {
        fromMember = teamMemberService.findByAccountId(fromAccountId);
      }

      log.info("담당자 변경: from={} (팀원={}), to={} (팀원={}), issueKey={}",
          fromAccountId, fromMember != null,
          fields.getAssignee() != null ? fields.getAssignee().getAccountId() : null,
          toMember != null,
          event.getIssue().getKey());

      // 팀원인 쪽만 추가: 양쪽 모두 팀원이면 2명, 한쪽만 팀원이면 1명
      if (fromMember != null) slackUserIds.add(fromMember.getSlackUserId());
      if (toMember != null) slackUserIds.add(toMember.getSlackUserId());

    } else {
      if (toMember != null) {
        slackUserIds.add(toMember.getSlackUserId());
        log.info("기타 변경, 현재 담당자(팀원) DM: name={}, issueKey={}",
            toMember.getName(), event.getIssue().getKey());
      } else {
        log.info("기타 변경, 현재 담당자(비팀원) → DM 없음: issueKey={}",
            event.getIssue().getKey());
      }
    }

    return slackUserIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .map(teamMemberService::findBySlackUserId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * changelog에 assignee 변경 항목이 있는지 확인한다.
   */
  private boolean hasAssigneeChangelog(JiraWebhookEvent event) {
    if (event.getChangelog() == null || event.getChangelog().getItems() == null)
      return false;
    return event.getChangelog().getItems().stream()
        .anyMatch(item -> "assignee".equalsIgnoreCase(item.getField()));
  }

  /**
   * changelog에서 이전 담당자(from)의 accountId를 추출한다.
   */
  private String getAssigneeFromAccountId(JiraWebhookEvent event) {
    if (event.getChangelog() == null || event.getChangelog().getItems() == null)
      return null;
    return event.getChangelog().getItems().stream()
        .filter(item -> "assignee".equalsIgnoreCase(item.getField()))
        .map(JiraWebhookEvent.ChangeItem::getFrom)
        .filter(from -> from != null && !from.isBlank())
        .findFirst()
        .orElse(null);
  }

  /**
   * 채널에 시간 헤더를 먼저, 본문 메시지를 이어서 전송한다.
   * 채널 전송 실패는 로그만 남기고 DM 전송은 계속 진행한다.
   */
  private void sendToChannel(JiraWebhookEvent event, ProjectRouting routing,
                             SlackClient slackClient) {
    try {
      String headerPayload = SlackBlockBuilder.forChannel(routing.getSlackChannelId())
          .fallbackText(SlackTimeHeaderBuilder.build())
          .noUnfurl()
          .build();
      slackClient.postMessage(headerPayload);

      String messageJson = JiraSlackMessageBuilder.formatChannelMessage(event, routing);
      slackClient.postMessage(messageJson);

      log.info("채널 알림 완료: issueKey={}, channel={}",
          event.getIssue().getKey(), routing.getSlackChannelId());

    } catch (Exception e) {
      log.error("채널 알림 전송 실패: issueKey={}", event.getIssue().getKey(), e);
    }
  }

  /**
   * 수신자 목록에 개인 DM을 전송한다. 개별 전송 실패는 로그만 남기고 이후 수신자 처리를 계속한다.
   */
  private void sendToIndividuals(JiraWebhookEvent event, SlackClient slackClient,
                                 List<TeamMember> recipients) {
    int success = 0, fail = 0;

    for (TeamMember member : recipients) {
      try {
        String messageJson = JiraSlackMessageBuilder.formatDmMessage(event, member);
        slackClient.postMessage(messageJson);
        success++;
        log.info("✅ DM 성공: name={}, issueKey={}",
            member.getName(), event.getIssue().getKey());
      } catch (Exception e) {
        fail++;
        log.error("❌ DM 실패: name={}, issueKey={}",
            member.getName(), event.getIssue().getKey(), e);
      }
    }

    log.info("DM 완료: issueKey={}, success={}, fail={}",
        event.getIssue().getKey(), success, fail);
  }

  /**
   * Secrets Manager에서 Bot 토큰을 조회한다. 5분 TTL 캐시를 적용하여 반복 조회를 최소화한다.
   */
  private String getBotToken(String secretName) {
    CachedToken cached = tokenCache.get(secretName);
    if (cached != null && !cached.isExpired()) return cached.token;

    try {
      GetSecretValueResponse response = secretsManagerClient.getSecretValue(
          GetSecretValueRequest.builder().secretId(secretName).build());

      Map<String, String> secret = objectMapper.readValue(
          response.secretString(), Map.class);
      String token = secret.get("token");
      tokenCache.put(secretName,
          new CachedToken(token, Instant.now().plusSeconds(TOKEN_CACHE_TTL_SECONDS)));
      log.info("Bot token 캐시: secretName={}", secretName);
      return token;

    } catch (Exception e) {
      log.error("Bot token 조회 실패: secretName={}", secretName, e);
      throw new ExternalApiClientException("SecretsManager",
          "Bot token 조회 실패: secretName=" + secretName, e);
    }
  }

  /**
   * 토큰 문자열을 TokenProvider 람다로 감싸 SlackClient를 생성한다.
   * package-private: 테스트에서 spy로 override 가능하도록 의도적으로 열어둠.
   */
  SlackClient buildSlackClient(String token) {
    TokenProvider tokenProvider = () -> token;
    return new SlackClient(tokenProvider);
  }

  /**
   * Bot 토큰 TTL 캐시 항목.
   */
  private static class CachedToken {
    final String token;
    final Instant expiresAt;

    CachedToken(String token, Instant expiresAt) {
      this.token = token;
      this.expiresAt = expiresAt;
    }

    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }
}
