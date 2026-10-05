package com.altronixsoft.workflow.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The rules from application.yml: GOLD 12%, STANDARD 18%, new 22%, fuel 8%, ADR 150, round to 1, valid 14 days.
 * price = round(cost × (1 + m) × (1 + f) + adr); margin = (price − cost) / price × 100, 2 decimals, half-up.
 *
 * <p>The expected values are worked out by hand:
 *
 * <pre>
 * 1000.00 GOLD          1000 × 1.12 = 1120.00;  × 1.08 = 1209.60;            → 1210;  210 / 1210 = 17.355 → 17.36
 * 1000.00 STANDARD      1000 × 1.18 = 1180.00;  × 1.08 = 1274.40;            → 1274;  274 / 1274 = 21.507 → 21.51
 * 1000.00 new           1000 × 1.22 = 1220.00;  × 1.08 = 1317.60;            → 1318;  318 / 1318 = 24.127 → 24.13
 * 1000.00 GOLD, ADR     1209.60 + 150 = 1359.60;                             → 1360;  360 / 1360 = 26.470 → 26.47
 *  421.52 STANDARD      421.52 × 1.18 = 497.3936; × 1.08 = 537.185088;       →  537;  115.48 / 537 = 21.504 → 21.50
 * 2500.00 new, ADR      2500 × 1.22 = 3050; × 1.08 = 3294; + 150 = 3444;     → 3444;  944 / 3444 = 27.410 → 27.41
 * </pre>
 */
class PricingCalculatorTest {

    static final PricingRules RULES = new PricingRules(
            Map.of(CustomerTier.GOLD, new BigDecimal("12"), CustomerTier.STANDARD, new BigDecimal("18")),
            new BigDecimal("22"),
            new BigDecimal("8"),
            new BigDecimal("150"),
            BigDecimal.ONE,
            14,
            5);

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private final PricingCalculator calculator = new PricingCalculator(RULES);

    @ParameterizedTest(name = "{0} {1} adr={2} → {3} ({4}%)")
    @CsvSource(
            nullValues = "NEW",
            value = {
                "1000.00, GOLD,     false, 1210.00, 17.36",
                "1000.00, STANDARD, false, 1274.00, 21.51",
                "1000.00, NEW,      false, 1318.00, 24.13",
                "1000.00, GOLD,     true,  1360.00, 26.47",
                "421.52,  STANDARD, false, 537.00,  21.50",
                "2500.00, NEW,      true,  3444.00, 27.41"
            })
    void pricesARateByTierAndAdr(
            BigDecimal cost, CustomerTier tier, boolean adr, BigDecimal expectedPrice, BigDecimal expectedMargin) {
        Quote quote = calculator.price(new CarrierRate("EuroLine", cost, "EUR", 2), tier, adr, TODAY);

        assertThat(quote.price()).isEqualTo(expectedPrice);
        assertThat(quote.marginPct()).isEqualTo(expectedMargin);
        assertThat(quote.cost()).isEqualByComparingTo(cost);
        assertThat(quote.carrier()).isEqualTo("EuroLine");
        assertThat(quote.currency()).isEqualTo("EUR");
        assertThat(quote.validUntil()).isEqualTo(TODAY.plusDays(14));
    }

    @Test
    void theBreakdownAddsUpToThePrice() {
        Quote quote = calculator.price(
                new CarrierRate("EuroLine", new BigDecimal("1000.00"), "EUR", 2), CustomerTier.GOLD, true, TODAY);

        assertThat(quote.breakdown())
                .containsExactly(
                        "cost 1000.00 EUR (EuroLine, 2 days)",
                        "margin 12% (GOLD): +120.00",
                        "fuel surcharge 8%: +89.60",
                        "ADR surcharge: +150.00",
                        "rounding: +0.40");
    }

    @Test
    void roundingCanGoDown() {
        Quote quote = calculator.price(
                new CarrierRate("BudgetTrans", new BigDecimal("421.52"), "EUR", 3),
                CustomerTier.STANDARD,
                false,
                TODAY);

        // 421.52 + 75.87 + 39.79 = 537.18, rounded to 537
        assertThat(quote.breakdown())
                .containsExactly(
                        "cost 421.52 EUR (BudgetTrans, 3 days)",
                        "margin 18% (STANDARD): +75.87",
                        "fuel surcharge 8%: +39.79",
                        "rounding: -0.18");
    }

    @Test
    void rulesWithoutAMarginForEveryTierAreRejected() {
        assertThatThrownBy(() -> new PricingRules(
                        Map.of(CustomerTier.GOLD, BigDecimal.TEN),
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        14,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("margin-by-tier");
    }
}
