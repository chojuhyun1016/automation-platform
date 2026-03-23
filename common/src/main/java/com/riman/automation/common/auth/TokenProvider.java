package com.riman.automation.common.auth;

/**
 * 인증 토큰 제공 인터페이스
 *
 * <p>clients 계층의 각 Client는 이 인터페이스만 의존한다.
 * 실제 구현체(환경변수, Secrets Manager 등)는 상위 모듈(scheduler, worker 등)이 주입한다.
 */
public interface TokenProvider {

    /**
     * 토큰 값 반환 (Bearer prefix 없이)
     */
    String getToken();

    /**
     * "Bearer {token}" 형식의 Authorization 헤더 값
     */
    default String toBearerHeader() {
        return "Bearer " + getToken();
    }

    /**
     * "Basic {base64}" 형식의 Authorization 헤더 값 — 서브클래스에서 override
     */
    default String toBasicHeader() {
        throw new UnsupportedOperationException("Basic auth not supported by this provider");
    }
}
