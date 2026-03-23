package com.riman.automation.common.auth;

import com.riman.automation.common.exception.ConfigException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Basic 인증 토큰 제공자
 *
 * <p>Jira Cloud는 email:apiToken을 Base64 인코딩한 Basic 인증 방식을 사용한다.
 *
 * <p>환경변수:
 * <ul>
 *   <li>JIRA_EMAIL     — 계정 이메일</li>
 *   <li>JIRA_API_TOKEN — Jira API 토큰</li>
 * </ul>
 */
public class BasicTokenProvider implements TokenProvider {

    private final String encodedCredentials;

    /**
     * @param emailEnvVar 이메일을 담은 환경변수명
     * @param tokenEnvVar 토큰을 담은 환경변수명
     */
    public BasicTokenProvider(String emailEnvVar, String tokenEnvVar) {
        String email = require(emailEnvVar);
        String token = require(tokenEnvVar);
        this.encodedCredentials = Base64.getEncoder()
                .encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getToken() {
        return encodedCredentials;
    }

    @Override
    public String toBasicHeader() {
        return "Basic " + encodedCredentials;
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new ConfigException("환경변수 미설정: " + name);
        return v;
    }
}
