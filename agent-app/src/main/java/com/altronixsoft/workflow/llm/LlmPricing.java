package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the models cost, in EUR per one million tokens: {@code llm.pricing.<model>.input/output}. A model is
 * matched by the longest configured name its reported name starts with ({@code gpt-4.1-mini} covers
 * {@code gpt-4.1-mini-2025-04-14}).
 */
@ConfigurationProperties("llm")
public record LlmPricing(Map<String, Price> pricing) {

    public LlmPricing {
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    public record Price(BigDecimal input, BigDecimal output) {

        public Price {
            input = input == null ? BigDecimal.ZERO : input;
            output = output == null ? BigDecimal.ZERO : output;
        }
    }
}
