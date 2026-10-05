package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldValidatorTest {

    private final FieldValidator validator = new FieldValidator();

    private static ExtractionBuilder complete() {
        return new ExtractionBuilder();
    }

    @Test
    void aCompleteRequestHasNothingMissing() {
        assertThat(validator.missing(complete().build())).isEmpty();
    }

    @Test
    void everyRequiredFieldIsReportedWhenAbsent() {
        Extraction empty = complete()
                .origin(null)
                .destination(" ")
                .pallets(null)
                .weight(null)
                .date(null)
                .build();

        assertThat(validator.missing(empty))
                .containsExactly("origin", "destination", "pallets", "weightKg", "pickupDate");
    }

    @Test
    void zeroOrNegativeQuantitiesCountAsMissing() {
        Extraction bad = complete().pallets(0).weight(new BigDecimal("-5")).build();

        assertThat(validator.missing(bad)).containsExactly("pallets", "weightKg");
    }

    @Test
    void theModelsOwnListOfMissingFieldsDoesNotDecide() {
        Extraction modelSaysMissing =
                complete().modelMissing(List.of("weightKg", "pickupDate")).build();

        assertThat(validator.missing(modelSaysMissing)).isEmpty();
    }

    @Test
    void cargoTypeIsOptional() {
        assertThat(validator.missing(complete().cargo(null).build())).isEmpty();
    }
}
