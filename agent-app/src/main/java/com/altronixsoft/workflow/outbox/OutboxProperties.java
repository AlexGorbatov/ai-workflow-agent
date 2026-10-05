package com.altronixsoft.workflow.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAttempts after this many failed sends a row is FAILED and counted in {@code outbox.failed}
 * @param retryBackoffBase the n-th failure waits {@code base * 2^(n-1)} before the next attempt
 */
@ConfigurationProperties("workflow.outbox")
public record OutboxProperties(Integer maxAttempts, Duration retryBackoffBase) {

    public OutboxProperties {
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        retryBackoffBase = retryBackoffBase == null ? Duration.ofSeconds(30) : retryBackoffBase;
    }
}
