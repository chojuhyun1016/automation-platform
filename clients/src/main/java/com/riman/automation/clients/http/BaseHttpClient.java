package com.riman.automation.clients.http;

import com.riman.automation.common.exception.ExternalApiClientException;
import com.riman.automation.common.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 클라이언트 추상 기반 클래스.
 * GET/POST 공통 실행 로직을 제공하며, 파싱과 비즈니스 검증은 서브클래스에서 담당한다.
 *
 * 예외 분리 원칙:
 *   HTTP 응답 수신 후 상태 코드 실패 -> ExternalApiException
 *   연결 실패, 타임아웃 등 통신 자체 실패 -> ExternalApiClientException
 */
@Slf4j
public abstract class BaseHttpClient {

  protected final String apiName;

  protected BaseHttpClient(String apiName) {
    this.apiName = apiName;
  }

  protected ApiResponse get(String url, Map<String, String> headers) {
    try {
      HttpGet request = new HttpGet(url);
      headers.forEach(request::setHeader);
      log.debug("[{}] GET {}", apiName, url);
      return execute(request);
    } catch (ExternalApiException | ExternalApiClientException e) {
      throw e;
    } catch (Exception e) {
      throw new ExternalApiClientException(apiName, "GET 실패: " + url, e);
    }
  }

  protected ApiResponse post(String url, Map<String, String> headers, String jsonBody) {
    try {
      HttpPost request = new HttpPost(url);
      headers.forEach(request::setHeader);
      request.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
      log.debug("[{}] POST {}", apiName, url);
      return execute(request);
    } catch (ExternalApiException | ExternalApiClientException e) {
      throw e;
    } catch (Exception e) {
      throw new ExternalApiClientException(apiName, "POST 실패: " + url, e);
    }
  }

  /**
   * HTTP 상태 코드가 2xx가 아니면 ExternalApiException을 던진다.
   */
  protected void requireSuccess(ApiResponse response, String operation) {
    if (!response.isSuccess()) {
      throw new ExternalApiException(
          apiName, response.getStatusCode(),
          operation + " 실패. body=" + response.getBody());
    }
  }

  protected ApiResponse execute(org.apache.hc.client5.http.classic.methods.HttpUriRequestBase request)
      throws Exception {
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpResponse response =
             SharedHttpClient.INSTANCE.execute(request)) {
      int code = response.getCode();
      String body = new String(
          response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
      log.debug("[{}] response status={}", apiName, code);
      return new ApiResponse(code, body);
    }
  }
}
