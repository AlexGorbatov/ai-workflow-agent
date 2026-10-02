package com.altronixsoft.workflow.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

@JsonTest
class QuoteContextJsonTest {

    @Autowired
    JsonMapper mapper;

    private static InboundEmail email() {
        return new InboundEmail(
                "<m1@nordline.test>",
                null,
                "anna@acme.test",
                "Quote Kyiv - Berlin",
                "Please quote 12 pallets.",
                Instant.parse("2026-09-30T08:15:30Z"));
    }

    @Test
    void fullContextSurvivesJsonRoundtrip() {
        QuoteContext ctx = new QuoteContext(
                email(),
                List.of("12 pallets, ADR class 3", "pickup Monday"),
                new QuoteRequest(
                        "Kyiv", "Berlin", new BigDecimal("4200.50"), 12, "ADR", LocalDate.of(2026, 10, 5), "en"),
                new Customer("C-1", "Acme GmbH", "anna@acme.test", CustomerTier.GOLD),
                List.of(
                        new CarrierRate("FastFreight", new BigDecimal("1000.00"), "EUR", 3),
                        new CarrierRate("SlowFreight", new BigDecimal("900.00"), "EUR", 6)),
                new Quote(
                        "FastFreight",
                        new BigDecimal("1000.00"),
                        new BigDecimal("1150.00"),
                        new BigDecimal("13.04"),
                        "EUR",
                        LocalDate.of(2026, 10, 12),
                        List.of("base 1000.00", "margin 150.00")),
                new PolicyDecision(false, List.of("dangerous goods")),
                new Investigation("summary", "cause", "action", List.of("evidence 1", "evidence 2")),
                "token-123",
                "<out-1@nordline.test>",
                Set.of("ADR", "HIGH_VALUE"),
                "done",
                "none");

        String json = mapper.writeValueAsString(ctx);
        QuoteContext back = mapper.readValue(json, QuoteContext.class);

        assertThat(back).isEqualTo(ctx);
    }

    @Test
    void approverPriceRecalculatesMargin() {
        Quote quote = new Quote(
                "FastFreight",
                new BigDecimal("1000"),
                new BigDecimal("1050"),
                Quote.marginPct(new BigDecimal("1000"), new BigDecimal("1050")),
                "EUR",
                LocalDate.of(2026, 10, 12),
                List.of("base"));

        Quote repriced = quote.withPrice(new BigDecimal("1100"));

        assertThat(repriced.marginPct()).isEqualByComparingTo("9.09");
        assertThat(repriced.price()).isEqualByComparingTo("1100");
        assertThat(repriced.cost()).isEqualTo(quote.cost());
        assertThat(repriced.breakdown()).isEqualTo(quote.breakdown());
    }

    @Test
    void marginIsZeroForMissingOrZeroPrice() {
        assertThat(Quote.marginPct(new BigDecimal("100"), null)).isEqualByComparingTo("0");
        assertThat(Quote.marginPct(new BigDecimal("100"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void withFlagsMergesInsteadOfReplacing() {
        QuoteContext ctx = QuoteContext.of(email()).withFlag("A").withFlags(Set.of("B"));

        assertThat(ctx.flags()).containsExactlyInAnyOrder("A", "B");
        assertThat(ctx.hasFlag("A")).isTrue();
    }

    @Test
    void fullTextIncludesReplies() {
        String text =
                QuoteContext.of(email()).withReply("first").withReply("second").fullText();

        assertThat(text)
                .startsWith("Subject: Quote Kyiv - Berlin\n\nPlease quote 12 pallets.")
                .contains("--- reply 1 ---\nfirst")
                .contains("--- reply 2 ---\nsecond");
    }

    @Test
    void nullCollectionsBecomeEmpty() {
        QuoteContext ctx =
                new QuoteContext(email(), null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(ctx.replies()).isNotNull().isEmpty();
        assertThat(ctx.rates()).isNotNull().isEmpty();
        assertThat(ctx.flags()).isNotNull().isEmpty();
    }

    @Test
    void dangerousCargoIsDetectedCaseInsensitively() {
        assertThat(new QuoteRequest("a", "b", null, null, "Adr class 3", null, "en").isDangerous())
                .isTrue();
        assertThat(new QuoteRequest("a", "b", null, null, "DANGEROUS goods", null, "en").isDangerous())
                .isTrue();
        assertThat(new QuoteRequest("a", "b", null, null, "reefer", null, "en").isDangerous())
                .isFalse();
        assertThat(new QuoteRequest("a", "b", null, null, null, null, "en").isDangerous())
                .isFalse();
    }
}
