package com.altronixsoft.workflow.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public record Quote(
        String carrier,
        BigDecimal cost,
        BigDecimal price,
        BigDecimal marginPct,
        String currency,
        LocalDate validUntil,
        List<String> breakdown) {

    public Quote {
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }

    /** {@code (price - cost) / price * 100}, scale 2, HALF_UP; a missing or zero price gives 0. */
    public static BigDecimal marginPct(BigDecimal cost, BigDecimal price) {
        if (price == null || price.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal safeCost = cost == null ? BigDecimal.ZERO : cost;
        return price.subtract(safeCost).multiply(BigDecimal.valueOf(100)).divide(price, 2, RoundingMode.HALF_UP);
    }

    public Quote withPrice(BigDecimal newPrice) {
        return new Quote(carrier, cost, newPrice, marginPct(cost, newPrice), currency, validUntil, breakdown);
    }
}
