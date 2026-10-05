package com.altronixsoft.workflow.mockrates;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateCalculatorTest {

    private final RatesProperties properties = new RatesProperties(
            List.of(
                    new RatesProperties.Lane("warszawa", "berlin", 575),
                    new RatesProperties.Lane("goteborg", "madrid", 3000)),
            List.of(
                    new RatesProperties.Carrier("FastFreight", new BigDecimal("0.30"), new BigDecimal("0.00012"), 700),
                    new RatesProperties.Carrier("BudgetTrans", new BigDecimal("0.20"), new BigDecimal("0.00009"), 400)),
            null);
    private final RateCalculator calculator = new RateCalculator(properties);

    @Test
    void costIsBasePlusPerKiloTimesWeight() {
        // FastFreight on 575 km: base 0.30 * 575 = 172.50, per kg 0.00012 * 575 = 0.069, 7200 kg -> 496.80
        List<CarrierRate> rates =
                calculator.rates("Warszawa", "Berlin", new BigDecimal("7200")).orElseThrow();

        CarrierRate fast = rates.stream()
                .filter(r -> r.carrier().equals("FastFreight"))
                .findFirst()
                .orElseThrow();
        assertThat(fast.cost()).isEqualByComparingTo("669.30");
        assertThat(fast.currency()).isEqualTo("EUR");
    }

    @Test
    void transitDaysRoundUpFromTheCarriersDailyDistance() {
        List<CarrierRate> rates =
                calculator.rates("Warszawa", "Berlin", new BigDecimal("1000")).orElseThrow();

        assertThat(rates)
                .extracting(CarrierRate::carrier, CarrierRate::transitDays)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FastFreight", 1),
                        org.assertj.core.groups.Tuple.tuple("BudgetTrans", 2));
    }

    @ParameterizedTest
    @CsvSource({
        "Warszawa, Berlin",
        "Warsaw, Berlin",
        "WARSZAWA, berlin",
        "  warszawa , Berlin ",
        "Berlin, Warszawa",
        "Göteborg, Madrid",
        "Goteborg, Madrid",
        "Gothenburg, Madrid"
    })
    void citiesMatchRegardlessOfCaseSpellingDiacriticsAndDirection(String origin, String destination) {
        assertThat(calculator.rates(origin, destination, new BigDecimal("1000")))
                .isPresent();
    }

    @Test
    void anUnknownLaneHasNoRates() {
        assertThat(calculator.rates("Warszawa", "Lisboa", new BigDecimal("1000")))
                .isEmpty();
        assertThat(calculator.rates(null, "Berlin", new BigDecimal("1000"))).isEmpty();
    }

    @Test
    void ratesAreSortedByCost() {
        List<CarrierRate> rates =
                calculator.rates("Warszawa", "Berlin", new BigDecimal("5000")).orElseThrow();

        assertThat(rates).extracting(CarrierRate::cost).isSorted();
        assertThat(rates.get(0).carrier()).isEqualTo("BudgetTrans");
    }

    @Test
    void aLongLaneTakesLongerAndSlowCarriersMuchLonger() {
        List<CarrierRate> rates =
                calculator.rates("Göteborg", "Madrid", new BigDecimal("1000")).orElseThrow();

        assertThat(rates)
                .extracting(CarrierRate::carrier, CarrierRate::transitDays)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FastFreight", 5),
                        org.assertj.core.groups.Tuple.tuple("BudgetTrans", 8));
    }

    @Test
    void optionalIsEmptyNotNullForUnknownLanes() {
        Optional<List<CarrierRate>> none = calculator.rates("x", "y", BigDecimal.ONE);
        assertThat(none).isEmpty();
    }
}
