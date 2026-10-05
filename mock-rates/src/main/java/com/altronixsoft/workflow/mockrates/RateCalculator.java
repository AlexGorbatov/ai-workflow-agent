package com.altronixsoft.workflow.mockrates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Prices a lane for each carrier. Deterministic: the same request always gives the same answer. */
@Component
class RateCalculator {

    /** Other spellings customers use, mapped to the form the lanes are written in. */
    private static final Map<String, String> ALIASES = Map.of(
            "warsaw", "warszawa",
            "munich", "munchen",
            "prague", "praha",
            "vienna", "wien",
            "milan", "milano",
            "gothenburg", "goteborg",
            "kiev", "kyiv");

    private final RatesProperties properties;

    RateCalculator(RatesProperties properties) {
        this.properties = properties;
    }

    /** Empty when the lane is not served. Cheapest first. */
    Optional<List<CarrierRate>> rates(String origin, String destination, BigDecimal weightKg) {
        String from = normalize(origin);
        String to = normalize(destination);
        if (from == null || to == null) {
            return Optional.empty();
        }
        return properties.lanes().stream()
                .filter(l -> l.a().equals(from) && l.b().equals(to) || l.a().equals(to) && l.b().equals(from))
                .findFirst()
                .map(lane -> properties.carriers().stream()
                        .map(c -> price(c, lane.distanceKm(), weightKg))
                        .sorted(Comparator.comparing(CarrierRate::cost))
                        .toList());
    }

    private static CarrierRate price(RatesProperties.Carrier carrier, int km, BigDecimal weightKg) {
        BigDecimal distance = BigDecimal.valueOf(km);
        BigDecimal base = carrier.baseEurPerKm().multiply(distance);
        BigDecimal perKg = carrier.perKgEurPerKm().multiply(distance);
        BigDecimal cost = base.add(perKg.multiply(weightKg)).setScale(2, RoundingMode.HALF_UP);
        int days = Math.max(1, (int) Math.ceil((double) km / carrier.kmPerDay()));
        return new CarrierRate(carrier.name(), cost, "EUR", days);
    }

    /** Lower case, no diacritics (ł has none to strip, so it is mapped by hand), aliases resolved. */
    static String normalize(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String s = city.strip().toLowerCase(Locale.ROOT).replace('ł', 'l').replace('ø', 'o');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return ALIASES.getOrDefault(s, s);
    }
}
