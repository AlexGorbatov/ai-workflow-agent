package com.altronixsoft.workflow.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a reply to the customer may say: the request as understood, the price and how long it holds. Never the
 * carrier cost, the margin or the policy's reasons. Both the model and the fallback templates get only this.
 */
public record QuoteFacts(
        String language,
        String customerName,
        String origin,
        String destination,
        BigDecimal weightKg,
        Integer pallets,
        String cargoType,
        LocalDate pickupDate,
        Integer transitDays,
        BigDecimal price,
        String currency,
        LocalDate validUntil) {

    public static QuoteFacts from(QuoteContext ctx) {
        QuoteRequest r = ctx.request();
        Quote q = ctx.quote();
        Integer days = ctx.rates().stream()
                .filter(rate -> q != null && rate.carrier().equals(q.carrier()))
                .map(CarrierRate::transitDays)
                .findFirst()
                .orElse(null);
        return new QuoteFacts(
                r == null || r.language() == null ? "en" : r.language(),
                ctx.customer() == null ? null : ctx.customer().name(),
                r == null ? null : r.origin(),
                r == null ? null : r.destination(),
                r == null ? null : r.weightKg(),
                r == null ? null : r.pallets(),
                r == null ? null : r.cargoType(),
                r == null ? null : r.pickupDate(),
                days,
                q == null ? null : q.price(),
                q == null ? null : q.currency(),
                q == null ? null : q.validUntil());
    }

    /** {@code {name}} placeholders for the mail templates; a missing fact becomes "-". */
    public Map<String, String> placeholders() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("customer", customerName == null ? "" : customerName);
        values.put("origin", text(origin));
        values.put("destination", text(destination));
        values.put(
                "weightKg",
                weightKg == null ? "-" : weightKg.stripTrailingZeros().toPlainString());
        values.put("pallets", pallets == null ? "-" : pallets.toString());
        values.put("cargoType", text(cargoType));
        values.put("pickupDate", pickupDate == null ? "-" : pickupDate.toString());
        values.put("transitDays", transitDays == null ? "-" : transitDays.toString());
        values.put(
                "price",
                price == null ? "-" : price.setScale(2, RoundingMode.HALF_UP).toPlainString());
        values.put("currency", text(currency));
        values.put("validUntil", validUntil == null ? "-" : validUntil.toString());
        return values;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
