package com.altronixsoft.workflow.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The quote policy from {@code policy/rules.yml}. Every value is required and checked at startup: a policy that
 * silently lost a rule would approve what it should not.
 *
 * @param blockingFlags any of these flags on the instance sends the quote to a person
 * @param maxAmount a price above this sends the quote to a person
 * @param minMarginPct a margin (percent of price) below this sends the quote to a person
 * @param approvalSla how long a quote waits for a person
 * @param tokenValidity how long the token of an automatic approval is valid
 */
@ConfigurationProperties("policy")
public record PolicyRules(
        List<String> blockingFlags,
        BigDecimal maxAmount,
        BigDecimal minMarginPct,
        Duration approvalSla,
        Duration tokenValidity) {

    public PolicyRules {
        if (blockingFlags == null || blockingFlags.isEmpty()) {
            throw new IllegalArgumentException("policy.blocking-flags must list at least one flag");
        }
        if (blockingFlags.stream().anyMatch(f -> f == null || f.isBlank())) {
            throw new IllegalArgumentException("policy.blocking-flags must not contain blank entries");
        }
        blockingFlags = List.copyOf(blockingFlags);
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("policy.max-amount must be positive");
        }
        if (minMarginPct == null || minMarginPct.signum() < 0 || minMarginPct.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("policy.min-margin-pct must be between 0 and 100");
        }
        positive("policy.approval-sla", approvalSla);
        positive("policy.token-validity", tokenValidity);
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
