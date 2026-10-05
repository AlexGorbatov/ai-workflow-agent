package com.altronixsoft.workflow.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The price of one carrier rate. A pure function of the rate, the customer tier, the ADR flag and the day: no
 * I/O, no model, nothing from the email text. Intermediate values keep full precision; only the final price is
 * rounded, half-up, to {@link PricingRules#roundTo()}.
 */
@Component
public class PricingCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PricingRules rules;

    public PricingCalculator(PricingRules rules) {
        this.rules = rules;
    }

    /**
     * @param tier the customer's tier; {@code null} for a new customer
     * @param dangerous whether the ADR surcharge applies
     */
    public Quote price(CarrierRate rate, CustomerTier tier, boolean dangerous, LocalDate today) {
        BigDecimal cost = rate.cost();
        BigDecimal marginPct = rules.marginFor(tier);
        BigDecimal withMargin = cost.multiply(BigDecimal.ONE.add(percent(marginPct)));
        BigDecimal withFuel = withMargin.multiply(BigDecimal.ONE.add(percent(rules.fuelSurchargePct())));
        BigDecimal adr = dangerous ? rules.adrSurcharge() : BigDecimal.ZERO;
        BigDecimal price = round(withFuel.add(adr));

        BigDecimal marginAmount = money(withMargin.subtract(cost));
        BigDecimal fuelAmount = money(withFuel.subtract(withMargin));
        BigDecimal rounding = price.subtract(money(cost))
                .subtract(marginAmount)
                .subtract(fuelAmount)
                .subtract(money(adr));

        List<String> breakdown = new ArrayList<>();
        breakdown.add("cost %s %s (%s, %d days)"
                .formatted(money(cost).toPlainString(), rate.currency(), rate.carrier(), rate.transitDays()));
        breakdown.add("margin %s%% (%s): %s"
                .formatted(plain(marginPct), tier == null ? "new customer" : tier.name(), signed(marginAmount)));
        breakdown.add("fuel surcharge %s%%: %s".formatted(plain(rules.fuelSurchargePct()), signed(fuelAmount)));
        if (dangerous) {
            breakdown.add("ADR surcharge: " + signed(money(adr)));
        }
        breakdown.add("rounding: " + signed(rounding));

        return new Quote(
                rate.carrier(),
                money(cost),
                price,
                Quote.marginPct(cost, price),
                rate.currency(),
                today.plusDays(rules.validityDays()),
                breakdown);
    }

    private BigDecimal round(BigDecimal amount) {
        BigDecimal step = rules.roundTo();
        return amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal percent(BigDecimal pct) {
        return pct.divide(HUNDRED);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal amount) {
        return (amount.signum() < 0 ? "" : "+") + amount.toPlainString();
    }
}
