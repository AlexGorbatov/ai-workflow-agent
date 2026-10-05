package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.outbox.OutboxEntry;
import com.altronixsoft.workflow.outbox.OutboxRepository;
import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The reply ladder on the stub model: v1, then strict, then the template; the guard decides at each rung. */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
class RespondStepIT {

    private static final String V1 = "You write the reply of a freight forwarder";
    private static final String STRICT = "STRICT MODE";

    @Autowired
    RespondStep step;

    @Autowired
    StubChatModel model;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void reset() {
        model.reset();
    }

    private static QuoteContext approved() {
        BigDecimal cost = new BigDecimal("1000.00");
        BigDecimal price = new BigDecimal("1210.00");
        return QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml"))
                .withRequest(new QuoteRequest(
                        "Warszawa",
                        "Berlin",
                        new BigDecimal("7200"),
                        12,
                        "household goods",
                        LocalDate.of(2026, 10, 6),
                        "en"))
                .withCustomer(new Customer(
                        "C-1001", "PolMarket Sp. z o.o.", "anna.kowalska@polmarket.test", CustomerTier.GOLD))
                .withRates(List.of(new CarrierRate("EuroLine", cost, "EUR", 2)))
                .withQuote(new Quote(
                        "EuroLine",
                        cost,
                        price,
                        Quote.marginPct(cost, price),
                        "EUR",
                        LocalDate.of(2026, 10, 19),
                        List.of("cost 1000.00 EUR (EuroLine, 2 days)", "margin 12% (GOLD): +120.00")));
    }

    private String reply(String body) {
        return json.writeValueAsString(new ReplyDraft("Your quote Warszawa - Berlin", body));
    }

    private OutboxEntry sent(UUID id) {
        return outbox.findByDedupeKey(id + ":QUOTE").orElseThrow();
    }

    @Test
    void aDraftThatPassesTheGuardIsQueuedAndTheModelNeverSeesCostOrMargin() {
        model.whenPromptContains(V1).replyWith(reply("Dear Anna, our price is 1210.00 EUR, valid until 2026-10-19."));
        UUID id = UUID.randomUUID();

        StepResult result = step.execute(id, approved());

        assertThat(result).isInstanceOfSatisfying(StepResult.Next.class, next -> {
            assertThat(next.next()).isEqualTo(QuoteState.RESPONDED);
            assertThat(next.ctx().outboundMessageId()).isEqualTo("<" + id + ".quote@nordline.test>");
            assertThat(next.ctx().flags()).doesNotContain(RespondStep.FALLBACK_TEMPLATE);
        });
        OutboxEntry mail = sent(id);
        assertThat(mail.getPayload().to()).isEqualTo("anna.kowalska@polmarket.test");
        assertThat(mail.getPayload().body()).contains("1210.00 EUR");
        assertThat(mail.getPayload().inReplyTo()).isEqualTo("<req-01.happy-path@polmarket.test>");
        assertThat(model.prompts())
                .singleElement()
                .satisfies(p -> assertThat(p.getContents())
                        .contains("1210")
                        .doesNotContain("1000.00", "17.36", "margin", "breakdown", "EuroLine")
                        .doesNotContain("could you please quote"));
    }

    @Test
    void aDraftWithAnotherPriceGetsAStrictRetryWithTheReason() {
        model.whenPromptContains(STRICT).replyWith(reply("Price: 1210.00 EUR. Valid until 2026-10-19."));
        model.whenPromptContains(V1).replyWith(reply("Special offer: 999.00 EUR, valid until 2026-10-19."));
        UUID id = UUID.randomUUID();

        StepResult.Next next = (StepResult.Next) step.execute(id, approved());

        assertThat(next.ctx().flags()).doesNotContain(RespondStep.FALLBACK_TEMPLATE);
        assertThat(sent(id).getPayload().body()).isEqualTo("Price: 1210.00 EUR. Valid until 2026-10-19.");
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1).getContents()).contains("an amount that is not the quoted price: 999.00 EUR");
    }

    @Test
    void aModelThatInventsThePriceTwiceIsReplacedByTheTemplate() {
        model.whenPromptContains(STRICT).replyWith(reply("Price: 1100.00 EUR, 10% off, valid until 2026-10-19."));
        model.whenPromptContains(V1).replyWith(reply("Special offer: 999.00 EUR, valid until 2026-10-19."));
        UUID id = UUID.randomUUID();

        StepResult.Next next = (StepResult.Next) step.execute(id, approved());

        assertThat(next.next()).isEqualTo(QuoteState.RESPONDED);
        assertThat(next.ctx().flags()).contains(RespondStep.FALLBACK_TEMPLATE);
        OutboxEntry mail = sent(id);
        assertThat(mail.getPayload().subject()).isEqualTo("Your freight quote: Warszawa - Berlin");
        assertThat(mail.getPayload().body())
                .contains("Hello PolMarket Sp. z o.o.,", "Price: 1210.00 EUR", "valid until 2026-10-19")
                .doesNotContain("999", "1100", "%");
    }

    @Test
    void aFailingModelFallsBackToTheTemplateInTheCustomersLanguage() {
        model.failWith(new IllegalStateException("model is down"));
        QuoteContext ctx = approved();
        ctx = ctx.withRequest(new QuoteRequest(
                "Warszawa", "Berlin", new BigDecimal("7200"), 12, "AGD", LocalDate.of(2026, 10, 6), "pl"));
        UUID id = UUID.randomUUID();

        StepResult.Next next = (StepResult.Next) step.execute(id, ctx);

        assertThat(next.ctx().flags()).contains(RespondStep.FALLBACK_TEMPLATE);
        assertThat(sent(id).getPayload().body()).contains("Cena: 1210.00 EUR", "ważna do 2026-10-19");
    }

    @Test
    void runningTheStepAgainQueuesNoSecondMail() {
        model.whenPromptContains(V1).replyWith(reply("Our price is 1210.00 EUR, valid until 2026-10-19."));
        UUID id = UUID.randomUUID();

        step.execute(id, approved());
        step.execute(id, approved());

        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(id)).hasSize(1);
    }
}
