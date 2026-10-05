package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private final CostCalculator costs = new CostCalculator(new LlmPricing(Map.of(
            "gpt-4.1", new LlmPricing.Price(new BigDecimal("1.84"), new BigDecimal("7.36")),
            "gpt-4.1-mini", new LlmPricing.Price(new BigDecimal("0.37"), new BigDecimal("1.47")),
            "local", new LlmPricing.Price(BigDecimal.ZERO, BigDecimal.ZERO))));

    @Test
    void inputAndOutputTokensArePricedPerMillion() {
        // 1200 × 0.37 + 300 × 1.47 = 444 + 441 = 885 per million → 0.000885
        assertThat(costs.cost("gpt-4.1-mini", 1200, 300)).isEqualTo(new BigDecimal("0.000885"));
    }

    @Test
    void theLongestMatchingNameWinsSoADatedModelFindsItsPrice() {
        assertThat(costs.cost("gpt-4.1-mini-2025-04-14", 1_000_000, 0)).isEqualTo(new BigDecimal("0.370000"));
        assertThat(costs.cost("gpt-4.1-2025-04-14", 1_000_000, 0)).isEqualTo(new BigDecimal("1.840000"));
    }

    @Test
    void theCostIsRoundedHalfUpToSixDecimals() {
        // 1 × 1.84 / 1e6 = 0.00000184 → 0.000002; 1 × 0.37 / 1e6 = 0.00000037 → 0.000000
        assertThat(costs.cost("gpt-4.1", 1, 0)).isEqualTo(new BigDecimal("0.000002"));
        assertThat(costs.cost("gpt-4.1-mini", 1, 0)).isEqualTo(new BigDecimal("0.000000"));
    }

    @Test
    void anUnknownOrLocalModelCostsNothing() {
        assertThat(costs.cost("llama-3.1-8b-instruct", 5000, 500)).isEqualTo(new BigDecimal("0.000000"));
        assertThat(costs.cost("local-qwen", 5000, 500)).isEqualTo(new BigDecimal("0.000000"));
        assertThat(costs.cost(null, null, null)).isEqualTo(new BigDecimal("0.000000"));
    }
}
