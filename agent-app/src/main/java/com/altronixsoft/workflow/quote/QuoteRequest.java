package com.altronixsoft.workflow.quote;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

public record QuoteRequest(
        String origin,
        String destination,
        BigDecimal weightKg,
        Integer pallets,
        String cargoType,
        LocalDate pickupDate,
        String language) {

    @JsonIgnore
    public boolean isDangerous() {
        if (cargoType == null) {
            return false;
        }
        String normalized = cargoType.toLowerCase(Locale.ROOT);
        return normalized.contains("adr") || normalized.contains("dangerous");
    }
}
