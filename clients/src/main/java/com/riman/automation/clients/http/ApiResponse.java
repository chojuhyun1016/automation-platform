package com.riman.automation.clients.http;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * HTTP API 응답 값 객체.
 * clients 계층 내부에서만 사용되며, 상위 계층에는 노출되지 않는다.
 */
@Getter
@RequiredArgsConstructor
public class ApiResponse {

  private final int statusCode;
  private final String body;

  /** 2xx 응답 여부. */
  public boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }

  /** 4xx 클라이언트 오류 여부. */
  public boolean isClientError() {
    return statusCode >= 400 && statusCode < 500;
  }

  /** 5xx 서버 오류 여부. */
  public boolean isServerError() {
    return statusCode >= 500;
  }

  @Override
  public String toString() {
    int preview = body != null ? Math.min(body.length(), 300) : 0;
    return "ApiResponse{status=" + statusCode
        + ", body=" + (body != null ? body.substring(0, preview) : "null") + "}";
  }
}
