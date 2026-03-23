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
 * Slack 알림 서비스
 *
 * <p><b>변경 사항:</b>
 * 자체 {@code CloseableHttpClient} + {@code HttpPost} 직접 사용 코드를
 * {@link SlackClient}(clients 계층)에 위임하도록 리팩토링.
 * HTTP 연결 관리, timeout 설정, Authorization 헤더 처리는 SlackClient가 담당한다.
 *
 * <p>채널 전송 시 시간 헤더({@link SlackTimeHeaderBuilder})를 먼저 전송 후
 * 본문 메시지를 전송하는 기존 순서를 유지한다.
 *
 * <pre>
 * ══════════════════════════════════════════════════════
 * 수정 내역
 * ══════════════════════════════════════════════════════
 *
 * [FIX-1] HttpClient hang 방지 (기존)
 *   → SlackClient(SharedHttpClient)가 connect 3s / response 10s timeout을
 *     내장하므로 별도 HttpClient 생성 불필요.
 *
 * [FIX-2] DM 수신자 로직 — from/to 기반 (기존 유지)
 *
 * [REFACTOR-1] HTTP 전송 → SlackClient 위임
 *   sendToChannel / sendToIndividuals / sendRawTextMessage 내부의
 *   HttpPost, CloseableHttpClient 코드를 SlackClient.postMessage()로 교체.
 *   SlackClient는 ok:false도 ExternalApiException으로 처리하므로
 *   응답 JSON ok 필드 수동 파싱이 불필요해진다.
 *
 * [REFACTOR-2] Bot 토큰 → TokenProvider + SlackClient 주입 패턴
 *   Secrets Manager에서 로드한 토큰을 EnvTokenProvider 대신
 *   람다식 TokenProvider로 SlackClient에 주입.
 *   토큰 캐시(TTL 5분) 로직은 기존과 동일하게 유지.
 *
 * [REFACTOR-3] 예외 통일
 *   RuntimeException → ExternalApiClientException (common.exception)
 * ══════════════════════════════════════════════════════
 * DM 발송 조건
 * ══════════════════════════════════════════════════════
 *
 * [담당자 변경 이벤트] changelog에 assignee 항목이 있는 경우
 *   팀원 → 팀원   : from + to 2명 모두
 *   팀원 → 비팀원 : from(팀원) 1명  ← 변경 시점에만 1회, 이후 비팀원 티켓 알림 없음
 *   비팀원 → 팀원 : to(팀원) 1명
 *   비팀원 → 비팀원 : 없음
 *
 * [기타 변경 이벤트] 기한/상태/우선순위 등
 *   현재 담당자 팀원  → 팀원 1명
 *   현재 담당자 비팀원 → 없음  ← 비팀원 티켓 변경 시 알림 불필요
 *
 * reporter, user(이벤트 트리거)는 수신자 제외.
 * </pre>
 */
@Slf4j
public class SlackNotificationService {

    private static final long TOKEN_CACHE_TTL_SECONDS = 300;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SecretsManagerClient secretsManagerClient;
    private final TeamMemberService teamMemberService;

    /**
     * secretName → CachedToken 캐시 (TTL: 5분).
     * SlackClient는 토큰별로 생성하므로 캐시에서 꺼낸 토큰으로 SlackClient를 즉시 구성한다.
     */
    private final Map<String, CachedToken> tokenCache = new HashMap<>();

    public SlackNotificationService() {
        this.secretsManagerClient = SecretsManagerClient.builder().build();
        this.teamMemberService = new TeamMemberService();
        log.info("SlackNotificationService 초기화 완료");
    }

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * Jira Webhook 이벤트 기반 Slack 알림 전송.
     *
     * <p>라우팅 설정에 따라 채널 전송 및 개인 DM 전송을 수행한다.
     *
     * @param event   Jira Webhook 이벤트
     * @param routing 프로젝트 라우팅 설정 (슬랙 채널, 토큰 시크릿, 전송 옵션 포함)
     */
    public void sendNotification(JiraWebhookEvent event, ProjectRouting routing) {
        try {
            // Secrets Manager에서 토큰 조회 → SlackClient 생성
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

    // =========================================================================
    // DM 수신자 결정 — 기존 로직 100% 유지
    // =========================================================================

    /**
     * DM 수신자 결정.
     *
     * <pre>
     * [담당자 변경 이벤트]
     *   팀원 → 팀원   : from + to 2명
     *   팀원 → 비팀원 : from(팀원) 1명
     *   비팀원 → 팀원 : to(팀원) 1명
     *   비팀원 → 비팀원 : 없음
     *
     * [기타 변경 이벤트]
     *   현재 담당자 팀원  : 1명
     *   현재 담당자 비팀원 : 없음
     * </pre>
     */
    private List<TeamMember> resolveTeamMemberRecipients(JiraWebhookEvent event) {
        // LinkedHashSet: 순서 유지 + slackUserId 중복 방지 (같은 사람이 from/to인 경우)
        Set<String> slackUserIds = new LinkedHashSet<>();

        JiraWebhookEvent.Fields fields = event.getIssue().getFields();

        // 현재 담당자(to)
        TeamMember toMember = null;
        if (fields.getAssignee() != null && fields.getAssignee().getAccountId() != null) {
            toMember = teamMemberService.findByAccountId(fields.getAssignee().getAccountId());
        }

        if (hasAssigneeChangelog(event)) {
            // ── 담당자 변경 이벤트 ──────────────────────────────────────────────
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

            // 팀원인 쪽만 추가 — 양쪽 모두 팀원이면 2명, 한쪽만 팀원이면 1명
            if (fromMember != null) slackUserIds.add(fromMember.getSlackUserId());
            if (toMember != null) slackUserIds.add(toMember.getSlackUserId());

        } else {
            // ── 기타 변경 이벤트 (기한/상태/우선순위 등) ─────────────────────────
            if (toMember != null) {
                slackUserIds.add(toMember.getSlackUserId());
                log.info("기타 변경, 현재 담당자(팀원) DM: name={}, issueKey={}",
                        toMember.getName(), event.getIssue().getKey());
            } else {
                log.info("기타 변경, 현재 담당자(비팀원) → DM 없음: issueKey={}",
                        event.getIssue().getKey());
            }
        }

        // slackUserId → TeamMember 변환 (null/blank 제거)
        return slackUserIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(teamMemberService::findBySlackUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean hasAssigneeChangelog(JiraWebhookEvent event) {
        if (event.getChangelog() == null || event.getChangelog().getItems() == null)
            return false;
        return event.getChangelog().getItems().stream()
                .anyMatch(item -> "assignee".equalsIgnoreCase(item.getField()));
    }

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

    // =========================================================================
    // 채널 전송
    // =========================================================================

    /**
     * 채널에 시간 헤더 + 본문 메시지를 전송한다.
     *
     * <p>SlackClient.postMessage()를 사용하므로 HttpPost 직접 생성이 불필요하다.
     * SlackClient는 ok:false도 예외로 처리하므로 응답 ok 필드를 수동 파싱하지 않는다.
     */
    private void sendToChannel(JiraWebhookEvent event, ProjectRouting routing,
                               SlackClient slackClient) {
        try {
            // 1. 시간 헤더 먼저 전송 (기존 순서 유지)
            String headerPayload = SlackBlockBuilder.forChannel(routing.getSlackChannelId())
                    .fallbackText(SlackTimeHeaderBuilder.build())
                    .noUnfurl()
                    .build();
            slackClient.postMessage(headerPayload);

            // 2. 본문 메시지 전송
            String messageJson = JiraSlackMessageBuilder.formatChannelMessage(event, routing);
            slackClient.postMessage(messageJson);

            log.info("채널 알림 완료: issueKey={}, channel={}",
                    event.getIssue().getKey(), routing.getSlackChannelId());

        } catch (Exception e) {
            // 채널 전송 실패는 로그만 남기고 DM 전송에는 영향 주지 않음
            log.error("채널 알림 전송 실패: issueKey={}", event.getIssue().getKey(), e);
        }
    }

    // =========================================================================
    // 개인 DM 전송
    // =========================================================================

    /**
     * 수신자 목록에게 개인 DM을 전송한다.
     *
     * <p>SlackClient.postMessage()를 사용하며, 개별 실패는 로그 후 계속 진행한다.
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

    // =========================================================================
    // Bot 토큰 — Secrets Manager + TTL 캐시 (기존 로직 유지)
    // =========================================================================

    /**
     * Secrets Manager에서 Bot 토큰을 조회한다 (TTL 5분 캐시).
     *
     * @param secretName Secrets Manager 시크릿 이름
     * @return Slack Bot 토큰
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
     * 토큰으로 {@link SlackClient}를 생성한다.
     *
     * <p>SlackClient는 TokenProvider를 주입받으므로,
     * 람다식 TokenProvider로 토큰 문자열을 래핑하여 전달한다.
     *
     * @param token Slack Bot 토큰
     * @return 생성된 SlackClient
     */
    private SlackClient buildSlackClient(String token) {
        // TokenProvider는 함수형 인터페이스 — 람다로 구현
        TokenProvider tokenProvider = () -> token;
        return new SlackClient(tokenProvider);
    }

    // =========================================================================
    // 내부 타입
    // =========================================================================

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
