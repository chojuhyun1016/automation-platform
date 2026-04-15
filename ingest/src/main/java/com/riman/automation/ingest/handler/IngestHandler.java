package com.riman.automation.ingest.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.riman.automation.common.util.SentryInitializer;
import com.riman.automation.ingest.facade.JiraWebhookFacade;
import com.riman.automation.ingest.facade.SlackFacade;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

/**
 * Lambda Entry Point — 요청 유형을 식별하여 담당 Facade로 위임한다.
 * 경로 라우팅은 /warmup, /slack/*, /webhook/jira 세 가지이며 나머지는 404를 반환한다.
 *
 * Map{@literal <}String, Object{@literal >} 입력 타입은 EventBridge Scheduler의 직접 Invoke
 * (raw JSON)와 API Gateway 프록시 페이로드를 모두 수신하기 위함이다.
 * path, headers, body 필드는 수동으로 추출한다.
 */
@Slf4j
public class IngestHandler
    implements RequestHandler<Map<String, Object>, APIGatewayProxyResponseEvent> {

  private static final String SLACK_PATH_PREFIX = "/slack/";
  private static final String JIRA_PATH = "/webhook/jira";

  private static final JiraWebhookFacade jiraFacade = new JiraWebhookFacade();
  private static final SlackFacade slackFacade = new SlackFacade();

  static {
    SentryInitializer.init("ingest");
  }

  public IngestHandler() {
    log.info("IngestHandler initialized");
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      Map<String, Object> event, Context context) {

    String path = event.containsKey("path")
        ? String.valueOf(event.get("path")) : null;

    String body = event.containsKey("body") && event.get("body") != null
        ? String.valueOf(event.get("body")) : "";

    @SuppressWarnings("unchecked")
    Map<String, String> headers = event.containsKey("headers") && event.get("headers") instanceof Map
        ? (Map<String, String>) event.get("headers")
        : Collections.emptyMap();

    log.info("Request: path={}, bodyLength={}, requestId={}",
        path, body.length(), context.getAwsRequestId());

    try {
      // path 미지정 또는 /warmup 은 EventBridge 직접 호출이거나 API Gateway warmup 요청이다.
      if (path == null || "/warmup".equals(path)) {
        log.info("Warmup 요청 수신 — Lambda warm 상태 유지 (path={})", path);
        return HttpResponse.ok("warm");
      }

      if (path.startsWith(SLACK_PATH_PREFIX)) {
        return slackFacade.handle(headers, body, path);
      }

      if (JIRA_PATH.equals(path)) {
        return jiraFacade.handle(body, context.getAwsRequestId());
      }

      log.warn("등록되지 않은 경로 요청: path={}, requestId={}",
          path, context.getAwsRequestId());
      return HttpResponse.notFound(path);
    } catch (Exception e) {
      log.error("예상치 못한 오류: path={}, requestId={}", path, context.getAwsRequestId(), e);
      SentryInitializer.captureException(e, "handleRequest");
      SentryInitializer.flush();
      // Slack 재시도 루프 방지를 위해 오류 시에도 200을 반환한다.
      return HttpResponse.ok("error");
    }
  }
}
