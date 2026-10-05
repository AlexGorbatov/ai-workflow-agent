package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.InboundEmail;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared fixtures for engine tests. */
final class TestData {

    private TestData() {}

    static InboundEmail email() {
        return new InboundEmail(
                "<" + UUID.randomUUID() + "@nordline.test>",
                null,
                "anna@acme.test",
                "Quote Kyiv - Berlin",
                "Please quote 12 pallets.",
                Instant.parse("2026-09-30T08:15:30Z"));
    }

    static QuoteContext simpleContext() {
        return QuoteContext.of(email());
    }

    static QuoteContext fullContext() {
        return new QuoteContext(
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
    }

    /** A step that does nothing; only {@code handles()} matters to the registry. */
    static Step stepFor(QuoteState state) {
        return new Step() {
            @Override
            public QuoteState handles() {
                return state;
            }

            @Override
            public StepResult execute(UUID instanceId, QuoteContext ctx) {
                return new StepResult.Next(QuoteState.CLOSED, ctx);
            }
        };
    }
}
