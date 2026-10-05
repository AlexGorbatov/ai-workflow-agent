package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param lowConfidence below this the extraction is flagged LOW_CONFIDENCE
 * @param largePallets at or above this many pallets the request is flagged LARGE_REQUEST
 * @param largeWeightKg at or above this weight the request is flagged LARGE_REQUEST
 */
@ConfigurationProperties("workflow.guards")
public record GuardProperties(Double lowConfidence, Integer largePallets, BigDecimal largeWeightKg) {

    public GuardProperties {
        lowConfidence = lowConfidence == null ? 0.7 : lowConfidence;
        largePallets = largePallets == null ? 33 : largePallets;
        largeWeightKg = largeWeightKg == null ? new BigDecimal("20000") : largeWeightKg;
    }
}
