package com.altronixsoft.workflow.quote;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a carrier cost becomes a price: {@code price = round(cost × (1 + margin) × (1 + fuel) + adr)}. Percentages
 * are written as percents (12 means 12%). Every value is required, so a typo in the YAML stops the application
 * instead of pricing with a zero.
 *
 * @param marginByTier markup on cost per customer tier, in percent
 * @param newCustomerMargin markup for a sender the CRM does not know, in percent
 * @param fuelSurchargePct fuel surcharge on cost plus margin, in percent
 * @param adrSurcharge flat surcharge for dangerous goods (ADR), in the rate's currency
 * @param roundTo the price is rounded half-up to a multiple of this (1 = whole units)
 * @param validityDays how long the quote is valid, from the day it is priced
 * @param maxTransitDays slower carriers are not offered
 */
@ConfigurationProperties("pricing")
public record PricingRules(
        Map<CustomerTier, BigDecimal> marginByTier,
        BigDecimal newCustomerMargin,
        BigDecimal fuelSurchargePct,
        BigDecimal adrSurcharge,
        BigDecimal roundTo,
        Integer validityDays,
        Integer maxTransitDays) {

    public PricingRules {
        if (marginByTier == null || !marginByTier.keySet().containsAll(Arrays.asList(CustomerTier.values()))) {
            throw new IllegalArgumentException(
                    "pricing.margin-by-tier must set every tier: " + Arrays.toString(CustomerTier.values()));
        }
        marginByTier.values().forEach(m -> nonNegative("pricing.margin-by-tier", m));
        marginByTier = Map.copyOf(new EnumMap<>(marginByTier));
        nonNegative("pricing.new-customer-margin", newCustomerMargin);
        nonNegative("pricing.fuel-surcharge-pct", fuelSurchargePct);
        nonNegative("pricing.adr-surcharge", adrSurcharge);
        if (roundTo == null
                || roundTo.signum() <= 0
                || roundTo.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("pricing.round-to must be positive, in whole cents at most");
        }
        if (validityDays == null || validityDays < 1) {
            throw new IllegalArgumentException("pricing.validity-days must be at least 1");
        }
        if (maxTransitDays == null || maxTransitDays < 1) {
            throw new IllegalArgumentException("pricing.max-transit-days must be at least 1");
        }
    }

    private static void nonNegative(String name, BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be set and not negative");
        }
    }

    /** The markup for a tier; {@code null} means a new customer. */
    public BigDecimal marginFor(CustomerTier tier) {
        return tier == null ? newCustomerMargin : marginByTier.get(tier);
    }
}
