package com.riman.automation.common.auth;

import com.riman.automation.common.exception.ConfigException;

/**
 * 환경변수에서 토큰을 읽는 구현체
 *
 * <p>Lambda 환경변수로 직접 토큰을 주입하는 가장 단순한 방식.
 * cold start 시 한 번만 읽어 캐싱한다.
 */
public class EnvTokenProvider implements TokenProvider {

    private final String token;

    public EnvTokenProvider(String envVarName) {
        String value = System.getenv(envVarName);
        if (value == null || value.isBlank()) {
            throw new ConfigException("환경변수 미설정: " + envVarName);
        }
        this.token = value;
    }

    @Override
    public String getToken() {
        return token;
    }
}
