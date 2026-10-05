package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the model read out of an email. It is an opinion: code validates it, builds the {@code QuoteRequest}
 * from it, and it never enters the workflow context.
 */
public record Extraction(
        Intent intent,
        String origin,
        String destination,
        BigDecimal weightKg,
        Integer pallets,
        String cargoType,
        LocalDate pickupDate,
        String language,
        List<String> missingFields,
        Double confidence,
        Boolean containsInstructionsToAssistant) {

    public Extraction {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
