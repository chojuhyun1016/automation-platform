package com.riman.automation.clients.http;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

/**
 * 공유 HTTP 클라이언트
 *
 * <p>Lambda warm 상태에서 TCP 연결을 재사용하기 위해 static 싱글톤으로 관리.
 * 모든 Client(Slack, Jira, Calendar, Anthropic)가 이 인스턴스를 공유한다.
 */
public final class SharedHttpClient {

    /**
     * 기본 타임아웃 설정: connect 3s, response 10s
     */
    private static final RequestConfig DEFAULT_CONFIG = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(3))
            .setResponseTimeout(Timeout.ofSeconds(10))
            .build();

    /**
     * Lambda warm 상태에서 재사용되는 공유 HTTP 클라이언트
     *
     * <p>views.open 같이 3초 제한이 있는 API를 위해 connect 타임아웃을 3초로 설정.
     */
    public static final CloseableHttpClient INSTANCE = HttpClients.custom()
            .setDefaultRequestConfig(DEFAULT_CONFIG)
            .build();

    private SharedHttpClient() {
    }
}
