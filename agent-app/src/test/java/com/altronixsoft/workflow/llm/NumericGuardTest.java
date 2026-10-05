package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.quote.QuoteFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Price 1210.00 EUR, pickup 2026-10-06, valid until 2026-10-19. */
class NumericGuardTest {

    private static final QuoteFacts FACTS = new QuoteFacts(
            "en",
            "PolMarket Sp. z o.o.",
            "Warszawa",
            "Berlin",
            new BigDecimal("7200"),
            12,
            "household goods",
            LocalDate.of(2026, 10, 6),
            2,
            new BigDecimal("1210.00"),
            "EUR",
            LocalDate.of(2026, 10, 19));

    private final NumericGuard guard = new NumericGuard();

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "plain price and both dates          | true  | Our price is 1210.00 EUR for pickup on 2026-10-06, valid until 2026-10-19.",
                "European format with a space        | true  | Der Preis beträgt 1 210,00 € und gilt bis 19.10.2026.",
                "thousands point, no decimals        | true  | Preis: 1.210 EUR.",
                "currency first, English grouping    | true  | Price: EUR 1,210.00, valid until October 19, 2026.",
                "Polish month name                   | true  | Cena 1210,00 EUR, ważna do 19 października 2026.",
                "Russian, евро                       | true  | Стоимость 1210 евро, действует до 19 октября 2026.",
                "Ukrainian, євро                     | true  | Вартість 1 210,00 євро, дійсна до 19 жовтня 2026.",
                "the weight is not money             | true  | 12 pallets, 7200 kg, for 1210.00 EUR.",
                "someone else's amount               | false | Our price is 999.00 EUR, valid until 2026-10-19.",
                "a second amount next to the price   | false | 1210.00 EUR, or 1100.00 EUR if you book today.",
                "a discount in percent               | false | 1210.00 EUR with a 10% discount.",
                "a discount in percent, German       | false | 1210,00 € abzüglich 90 % Rabatt.",
                "a date that is not ours             | false | 1210.00 EUR, valid until 2026-11-30.",
                "a made-up date in words             | false | 1210.00 EUR, pickup on 3 November 2026.",
                "no amount at all                    | false | Thank you for your request, we will send a quote soon."
            })
    void checksEveryAmountPercentageAndDate(String name, boolean passes, String body) {
        assertThat(guard.check(body, FACTS).isEmpty())
                .as(guard.check(body, FACTS).toString())
                .isEqualTo(passes);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"1 210,00 | 1210.00", "1.210,00 | 1210.00", "1,210.00 | 1210.00", "1210 | 1210", "1210,5 | 1210.5"
            })
    void normalizesAmounts(String raw, BigDecimal expected) {
        assertThat(NumericGuard.normalize(raw)).isEqualByComparingTo(expected);
    }
}
