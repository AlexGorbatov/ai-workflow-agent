package com.altronixsoft.workflow.llm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A complete, unremarkable extraction that tests bend one field at a time. */
class ExtractionBuilder {

    private Intent intent = Intent.QUOTE_REQUEST;
    private String origin = "Warszawa";
    private String destination = "Berlin";
    private BigDecimal weight = new BigDecimal("7200");
    private Integer pallets = 12;
    private String cargo = "household goods";
    private LocalDate date = LocalDate.of(2026, 10, 6);
    private String language = "en";
    private List<String> modelMissing = List.of();
    private Double confidence = 0.95;
    private Boolean instructions = false;

    ExtractionBuilder intent(Intent v) {
        intent = v;
        return this;
    }

    ExtractionBuilder origin(String v) {
        origin = v;
        return this;
    }

    ExtractionBuilder destination(String v) {
        destination = v;
        return this;
    }

    ExtractionBuilder weight(BigDecimal v) {
        weight = v;
        return this;
    }

    ExtractionBuilder pallets(Integer v) {
        pallets = v;
        return this;
    }

    ExtractionBuilder cargo(String v) {
        cargo = v;
        return this;
    }

    ExtractionBuilder date(LocalDate v) {
        date = v;
        return this;
    }

    ExtractionBuilder language(String v) {
        language = v;
        return this;
    }

    ExtractionBuilder modelMissing(List<String> v) {
        modelMissing = v;
        return this;
    }

    ExtractionBuilder confidence(Double v) {
        confidence = v;
        return this;
    }

    ExtractionBuilder instructions(Boolean v) {
        instructions = v;
        return this;
    }

    Extraction build() {
        return new Extraction(
                intent,
                origin,
                destination,
                weight,
                pallets,
                cargo,
                date,
                language,
                modelMissing,
                confidence,
                instructions);
    }
}
