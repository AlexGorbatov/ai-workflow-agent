package com.altronixsoft.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.Quote;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The rules from policy/rules.yml: blocking flags, price above 5000, margin below 10%. */
class PolicyEngineTest {

    static final PolicyRules RULES = new PolicyRules(
            List.of(
                    "SUSPICIOUS_INSTRUCTIONS",
                    "LOW_CONFIDENCE",
                    "NEW_CUSTOMER",
                    "CREDIT_HOLD",
                    "LARGE_REQUEST",
                    "DANGEROUS_GOODS"),
            new BigDecimal("5000"),
            new BigDecimal("10"),
            Duration.ofHours(24),
            Duration.ofDays(7));

    private final PolicyEngine engine = new PolicyEngine(RULES);

    private static Quote quote(String cost, String price) {
        BigDecimal c = new BigDecimal(cost);
        BigDecimal p = new BigDecimal(price);
        return new Quote("EuroLine", c, p, Quote.marginPct(c, p), "EUR", null, null);
    }

    private static Set<String> flags(String spaceSeparated) {
        return spaceSeparated.isBlank()
                ? Set.of()
                : Arrays.stream(spaceSeparated.split(" ")).collect(Collectors.toSet());
    }

    private static List<String> reasons(String spaceSeparated) {
        return spaceSeparated.isBlank() ? List.of() : List.of(spaceSeparated.split(" "));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "nothing in the way            |                                      | 1000 | 1210 | true  |",
                "suspicious instructions       | SUSPICIOUS_INSTRUCTIONS              | 1000 | 1210 | false | flag:SUSPICIOUS_INSTRUCTIONS",
                "new customer                  | NEW_CUSTOMER                         | 1000 | 1318 | false | flag:NEW_CUSTOMER",
                "credit hold                   | CREDIT_HOLD                          | 1000 | 1210 | false | flag:CREDIT_HOLD",
                "price above the limit         |                                      | 4200 | 5082 | false | amount>5000",
                "margin below the minimum      |                                      | 1000 | 1100 | false | margin<10",
                "a flag that does not block    | REPLIED_TWICE                        | 1000 | 1210 | true  |",
                "flags, amount and margin      | LOW_CONFIDENCE DANGEROUS_GOODS       | 5000 | 5400 | false | flag:LOW_CONFIDENCE flag:DANGEROUS_GOODS amount>5000 margin<10"
            })
    void decidesFromFlagsAndNumbers(
            String name, String flags, String cost, String price, boolean auto, String reasons) {
        PolicyDecision decision = engine.decide(flags(flags == null ? "" : flags), quote(cost, price));

        assertThat(decision.auto()).isEqualTo(auto);
        assertThat(decision.reasons()).containsExactlyElementsOf(reasons(reasons == null ? "" : reasons));
    }

    @Test
    void aPriceExactlyAtTheLimitIsAllowed() {
        assertThat(engine.decide(Set.of(), quote("4000", "5000")).auto()).isTrue();
    }

    @Test
    void noQuoteIsNeverApproved() {
        PolicyDecision decision = engine.decide(Set.of(), null);

        assertThat(decision.auto()).isFalse();
        assertThat(decision.reasons()).containsExactly(PolicyEngine.NO_QUOTE);
    }

    @Test
    void rulesWithoutBlockingFlagsOrLimitsDoNotStart() {
        assertThatThrownBy(() -> new PolicyRules(
                        List.of(), BigDecimal.TEN, BigDecimal.ONE, Duration.ofHours(1), Duration.ofDays(1)))
                .hasMessageContaining("blocking-flags");
        assertThatThrownBy(() ->
                        new PolicyRules(List.of("X"), null, BigDecimal.ONE, Duration.ofHours(1), Duration.ofDays(1)))
                .hasMessageContaining("max-amount");
        assertThatThrownBy(() -> new PolicyRules(
                        List.of("X"), BigDecimal.TEN, new BigDecimal("100"), Duration.ofHours(1), Duration.ofDays(1)))
                .hasMessageContaining("min-margin-pct");
    }
}
