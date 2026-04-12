package com.riman.automation.common.util;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sentry 에러 모니터링 초기화 유틸리티.
 *
 * <p>SENTRY_DSN 환경변수가 설정된 경우에만 활성화된다.
 * 미설정 시 모든 메서드는 no-op으로 동작한다.
 *
 * <p>각 Lambda Handler의 static 블록 또는 생성자에서 {@link #init(String)}을 호출하여
 * 모듈명을 태그로 등록한다. 예외 발생 시 {@link #captureException(Throwable)}으로 전송.
 */
public final class SentryInitializer {

    private static final Logger log = LoggerFactory.getLogger(SentryInitializer.class);

    private static volatile boolean initialized = false;

    private SentryInitializer() {}

    /**
     * Sentry를 초기화한다.
     *
     * @param moduleName 모듈명 (예: "ingest", "worker", "scheduler", "groupware")
     */
    public static synchronized void init(String moduleName) {
        if (initialized) return;

        String dsn = System.getenv("SENTRY_DSN");
        if (dsn == null || dsn.isBlank()) {
            log.info("[SentryInitializer] SENTRY_DSN 미설정 → Sentry 비활성");
            initialized = true;
            return;
        }

        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(getEnvOrDefault("SENTRY_ENVIRONMENT", "production"));
            options.setRelease(getEnvOrDefault("SENTRY_RELEASE", "automation-platform@1.0.0"));
            options.setTag("module", moduleName);
            options.setTag("runtime", "aws-lambda");
            options.setTag("region", getEnvOrDefault("AWS_REGION", "ap-northeast-2"));
            // Lambda 환경에서는 shutdown hook이 동작하지 않으므로 즉시 전송
            options.setFlushTimeoutMillis(2000);
        });

        log.info("[SentryInitializer] Sentry 초기화 완료: module={}", moduleName);
        initialized = true;
    }

    /**
     * 예외를 Sentry에 전송한다. 초기화되지 않았거나 DSN 미설정 시 no-op.
     */
    public static void captureException(Throwable throwable) {
        if (!Sentry.isEnabled()) return;
        Sentry.captureException(throwable);
    }

    /**
     * 예외를 Sentry에 전송하면서 추가 컨텍스트를 설정한다.
     *
     * @param throwable 예외
     * @param operation 실패한 작업명 (예: "processJiraEvent", "runDaily")
     */
    public static void captureException(Throwable throwable, String operation) {
        if (!Sentry.isEnabled()) return;
        Sentry.withScope(scope -> {
            scope.setTag("operation", operation);
            scope.setLevel(SentryLevel.ERROR);
            Sentry.captureException(throwable);
        });
    }

    /**
     * Sentry 이벤트 버퍼를 즉시 플러시한다. Lambda 종료 전 호출 권장.
     */
    public static void flush() {
        if (!Sentry.isEnabled()) return;
        Sentry.flush(2000);
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
