package com.riman.automation.ingest.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.riman.automation.ingest.facade.JiraWebhookFacade;
import com.riman.automation.ingest.facade.SlackFacade;
import com.riman.automation.ingest.util.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

/**
 * Lambda Entry Point — 요청 유형을 식별하여 담당 Facade로 위임한다.
 *
 * <pre>
 *   경로 라우팅:
 *     /warmup        →  warmup 응답 (EventBridge Scheduler 직접 호출 포함)
 *     /slack/*       →  SlackFacade        (서명 검증 포함)
 *     /webhook/jira  →  JiraWebhookFacade
 *     그 외          →  404 반환
 * </pre>
 *
 * <p><b>Map&lt;String, Object&gt; 사용 이유:</b><br>
 * EventBridge Scheduler는 Lambda를 직접 Invoke하며 raw JSON 페이로드를 전달한다.
 * 기존 RequestHandler&lt;APIGatewayProxyRequestEvent, ...&gt;는 API Gateway 형식만 처리하므로
 * EventBridge 직접 호출 시 역직렬화 실패로 warmup 로그가 전혀 찍히지 않는 문제가 있었다.
 * Map&lt;String, Object&gt;로 변경하면 모든 JSON 형식을 수신할 수 있다.
 * path, headers, body 필드는 수동으로 추출하여 기존 로직과 동일하게 처리한다.
 */
@Slf4j
public class IngestHandler
        implements RequestHandler<Map<String, Object>, APIGatewayProxyResponseEvent> {

    private static final String SLACK_PATH_PREFIX = "/slack/";
    private static final String JIRA_PATH = "/webhook/jira";

    private static final JiraWebhookFacade jiraFacade = new JiraWebhookFacade();
    private static final SlackFacade slackFacade = new SlackFacade();

    public IngestHandler() {
        log.info("IngestHandler initialized");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            Map<String, Object> event, Context context) {

        // path 추출 — API Gateway: "path" 필드, EventBridge: "path" 필드 (동일)
        String path = event.containsKey("path")
                ? String.valueOf(event.get("path")) : null;

        // body 추출
        String body = event.containsKey("body") && event.get("body") != null
                ? String.valueOf(event.get("body")) : "";

        // headers 추출 — API Gateway는 Map<String,String> 형태로 전달
        @SuppressWarnings("unchecked")
        Map<String, String> headers = event.containsKey("headers") && event.get("headers") instanceof Map
                ? (Map<String, String>) event.get("headers")
                : Collections.emptyMap();

        log.info("Request: path={}, bodyLength={}, requestId={}",
                path, body.length(), context.getAwsRequestId());

        // warmup 처리 — EventBridge Scheduler 직접 호출 및 API Gateway /warmup 모두 처리
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
    }
}
