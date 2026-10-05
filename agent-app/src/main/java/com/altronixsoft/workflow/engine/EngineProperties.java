package com.altronixsoft.workflow.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAttempts a step that fails this many times in a row sends the instance to EXCEPTION
 * @param retryBackoffBase the n-th failure waits {@code base * 2^n} before the next attempt
 */
@ConfigurationProperties("workflow.engine")
public record EngineProperties(Integer maxAttempts, Duration retryBackoffBase) {

    public EngineProperties {
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        retryBackoffBase = retryBackoffBase == null ? Duration.ofSeconds(1) : retryBackoffBase;
    }
}
