package com.riman.automation.scheduler.service.report;

import com.riman.automation.clients.anthropic.AnthropicClient;
import com.riman.automation.common.slack.SlackBlockBuilder;
import com.riman.automation.scheduler.dto.s3.DailyReportConfig;
import com.riman.automation.scheduler.dto.report.DailyReportData;
import com.riman.automation.scheduler.service.format.DailyReportFormatter;
import com.riman.automation.scheduler.service.load.ReportRulesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 선택적 AI 보고서 다듬기 서비스이다.
 * DailyReportFormatter가 만든 plain text 보고서를 Claude API로 자연스럽게 다듬은 뒤
 * 최종 Slack Block Kit JSON을 반환하며, AnthropicClient가 null이거나 호출 실패 시 원본 포맷으로 폴백한다.
 * S3의 rules/DAILY_REPORT_RULES.md를 system 프롬프트로 주입하여 코드 배포 없이 규칙을 갱신할 수 있다.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyReportService {

  private final AnthropicClient anthropicClient;
  private final DailyReportFormatter formatter;
  private final ReportRulesService rulesLoader;

  /**
   * Claude API로 보고서를 자연어 다듬기하고 Slack Block Kit JSON을 반환한다.
   * AI 호출 실패 시 원본 포맷으로 폴백한다.
   *
   * @param channelId 전송 채널 ID
   * @param data      보고서 데이터
   * @param config    보고서 설정
   */
  public String refineAndFormat(String channelId, DailyReportData data, DailyReportConfig config) {
    if (anthropicClient == null) {
      log.info("[DailyReportService] AnthropicClient 없음, 원본 포맷 사용");
      return formatter.format(channelId, data, config);
    }

    try {
      String systemPrompt = rulesLoader.loadDailyRules();
      String plainText = formatter.formatAsPlainText(data, config);

      String userMessage = buildUserMessage(plainText, data);
      String refined = anthropicClient.complete(systemPrompt, userMessage);

      log.info("[DailyReportService] Claude 다듬기 완료");
      return wrapIntoSlackJson(channelId, refined, data);

    } catch (Exception e) {
      log.error("[DailyReportService] Claude 호출 실패, 원본 포맷으로 폴백", e);
      return formatter.format(channelId, data, config);
    }
  }

  private String buildUserMessage(String plainText, DailyReportData data) {
    return "아래 일일 보고서 데이터를 규칙에 따라 Slack mrkdwn 형식으로 정리해주세요.\n"
        + "섹션 순서, 이모지, 색깔 규칙은 반드시 유지하세요.\n"
        + "링크(<url|title>)는 절대 변경하지 마세요.\n\n"
        + "=== 보고서 원본 ===\n"
        + plainText;
  }

  /**
   * Claude 반환 텍스트를 단일 section 블록으로 감싸 최종 JSON을 생성한다.
   * Claude 응답이 이미 Slack mrkdwn이므로 section 하나에 그대로 넣고 헤더를 분리한다.
   */
  private String wrapIntoSlackJson(String channelId, String refinedText, DailyReportData data) {
    return SlackBlockBuilder.forChannel(channelId)
        .fallbackText("📊 일일 팀 보고서 | "
            + com.riman.automation.common.util.DateTimeUtil.formatDisplay(data.getBaseDate()))
        .noUnfurl()
        .section(refinedText)
        .context("_발송: "
            + com.riman.automation.common.util.DateTimeUtil.formatDateTime(
            com.riman.automation.common.util.DateTimeUtil.nowKst()) + " KST_")
        .build();
  }
}
