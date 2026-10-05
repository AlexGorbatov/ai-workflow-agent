package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The cost of one model call in EUR, 6 decimals, half-up: {@code (in × input + out × output) / 1 000 000}. A model
 * without a price costs 0 and is logged once, so a missing price shows up instead of hiding behind a zero.
 */
@Component
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmPricing pricing;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CostCalculator(LlmPricing pricing) {
        this.pricing = pricing;
    }

    public BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        Optional<LlmPricing.Price> price = priceOf(model);
        if (price.isEmpty()) {
            if (model != null && warned.add(model)) {
                log.warn("No price for model {}: its calls are counted at 0 EUR (set llm.pricing.{})", model, model);
            }
            return BigDecimal.ZERO.setScale(6);
        }
        BigDecimal in = BigDecimal.valueOf(inputTokens == null ? 0 : inputTokens)
                .multiply(price.get().input());
        BigDecimal out = BigDecimal.valueOf(outputTokens == null ? 0 : outputTokens)
                .multiply(price.get().output());
        return in.add(out).divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private Optional<LlmPricing.Price> priceOf(String model) {
        if (model == null) {
            return Optional.empty();
        }
        return pricing.pricing().entrySet().stream()
                .filter(e -> model.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }
}
