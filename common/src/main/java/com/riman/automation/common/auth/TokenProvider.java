package com.riman.automation.common.auth;

/**
 * 인증 토큰 제공 인터페이스.
 * clients 계층의 각 Client는 이 인터페이스만 의존하며, 실제 구현체는 상위 모듈이 주입한다.
 */
public interface TokenProvider {

  /**
   * 토큰 값 반환 (Bearer prefix 없이).
   */
  String getToken();

  /**
   * "Bearer {token}" 형식의 Authorization 헤더 값을 반환한다.
   */
  default String toBearerHeader() {
    return "Bearer " + getToken();
  }

  /**
   * "Basic {base64}" 형식의 Authorization 헤더 값을 반환한다.
   * 서브클래스에서 override하여 사용한다.
   */
  default String toBasicHeader() {
    throw new UnsupportedOperationException("Basic auth not supported by this provider");
  }
}
