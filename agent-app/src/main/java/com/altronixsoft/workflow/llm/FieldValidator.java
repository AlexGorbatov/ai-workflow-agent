package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decides, in code, which facts of a quote request are still missing. The model's own list of missing
 * fields is advice; this is the rule.
 */
@Component
public class FieldValidator {

    /** Names of the missing fields, in a stable order. Empty means the request is complete. */
    public List<String> missing(Extraction x) {
        List<String> missing = new ArrayList<>();
        if (blank(x.origin())) {
            missing.add("origin");
        }
        if (blank(x.destination())) {
            missing.add("destination");
        }
        if (x.pallets() == null || x.pallets() <= 0) {
            missing.add("pallets");
        }
        if (x.weightKg() == null || x.weightKg().compareTo(BigDecimal.ZERO) <= 0) {
            missing.add("weightKg");
        }
        if (x.pickupDate() == null) {
            missing.add("pickupDate");
        }
        return missing;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
