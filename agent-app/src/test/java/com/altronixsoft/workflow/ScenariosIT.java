package com.altronixsoft.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.workflow.approval.ApprovalService;
import com.altronixsoft.workflow.approval.ApprovalTask;
import com.altronixsoft.workflow.approval.ApprovalTaskRepository;
import com.altronixsoft.workflow.approval.Decision;
import com.altronixsoft.workflow.crm.RecordStep;
import com.altronixsoft.workflow.engine.FollowUpHandler;
import com.altronixsoft.workflow.engine.Signal;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.Guards;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.ReplyDraft;
import com.altronixsoft.workflow.llm.RespondStep;
import com.altronixsoft.workflow.outbox.OutboxEntry;
import com.altronixsoft.workflow.outbox.OutboxRelay;
import com.altronixsoft.workflow.outbox.OutboxRepository;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.ToolCall;
import com.altronixsoft.workflow.tools.ToolCallRepository;
import com.altronixsoft.workflow.tools.ToolCallStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The five end-to-end scenarios of M6 on the real steps: Testcontainers (PostgreSQL, Mailpit), the mock CRM and
 * rates as processes, the stub model. Mails leave through the real outbox relay into Mailpit; approvals are
 * decided through ApprovalService; time-outs are fired by calling the engine, as the scheduler would.
 */
@IntegrationTest
// require-all-states as in production: with the real steps, every working state must have one
@TestPropertySource(properties = {"workflow.real-steps=true", "workflow.registry.require-all-states=true"})
@Import(MockApps.class)
class ScenariosIT {

    private static final String RESPOND = "You write the reply of a freight forwarder";
    private static final String STRICT = "STRICT MODE";

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    ApprovalTaskRepository approvals;

    @Autowired
    ApprovalService approvalService;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ToolCallRepository toolCalls;

    @Autowired
    StubChatModel model;

    @Autowired
    JsonMapper json;

    @Autowired
    JavaMailSender sender;

    @Value("${workflow.mail.mailpit-url}")
    String mailpitUrl;

    @BeforeEach
    void reset() {
        model.reset();
    }

    // --- the model's answers

    private void understands(String subjectKeyword, Extraction extraction) {
        model.whenPromptContains(subjectKeyword).replyWith(json.writeValueAsString(extraction));
    }

    private void replies(String marker, String body) {
        model.whenPromptContains(marker).replyWith(json.writeValueAsString(new ReplyDraft("Your freight quote", body)));
    }

    private static Extraction extraction(
            String from,
            String to,
            String weightKg,
            int pallets,
            String cargo,
            LocalDate pickup,
            String lang,
            boolean injected) {
        return new Extraction(
                Intent.QUOTE_REQUEST,
                from,
                to,
                weightKg == null ? null : new BigDecimal(weightKg),
                pallets,
                cargo,
                pickup,
                lang,
                weightKg == null ? List.of("weightKg") : List.of(),
                0.95,
                injected);
    }

    private static final Extraction GOLD_01 =
            extraction("Warszawa", "Berlin", "7200", 12, "household goods", LocalDate.of(2026, 10, 6), "en", false);

    // --- driving the instance

    private UUID start(String sample) {
        return engine.start("scenario-" + UUID.randomUUID(), QuoteContext.of(SampleEmails.read(sample)))
                .orElseThrow();
    }

    private WorkflowInstance instance(UUID id) {
        return instances.findById(id).orElseThrow();
    }

    private void awaitState(UUID id, QuoteState state) {
        await().atMost(20, TimeUnit.SECONDS).until(() -> instance(id).getState() == state);
    }

    private void approve(UUID id) {
        ApprovalTask task = approvals.findByInstanceIdOrderByCreatedAtAsc(id).getLast();
        approvalService.decide(task.getId(), new Decision(Decision.Action.APPROVE, null, null, null), "max");
    }

    /** Sends what is due and returns the quote mail as Mailpit received it. */
    private MailpitTestClient.Summary deliveredQuote(UUID id, String to) {
        relay.dispatchOnce();
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, sender);
        String messageId = id + ".quote@nordline.test";
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> mailpit.messagesTo(to).stream().anyMatch(m -> messageId.equals(m.messageId())));
        return mailpit.messagesTo(to).stream()
                .filter(m -> messageId.equals(m.messageId()))
                .findFirst()
                .orElseThrow();
    }

    private OutboxEntry quoteMail(UUID id) {
        return outbox.findByDedupeKey(id + ":QUOTE").orElseThrow();
    }

    private List<ToolCall> crmWrites(UUID id) {
        return toolCalls.findByInstanceIdOrderByCreatedAtAsc(id).stream()
                .filter(c -> c.getTool().equals("createOpportunity"))
                .toList();
    }

    // --- the scenarios

    @Test
    void s1_aGoldCustomerWithCompleteDataGetsAnAutomaticQuoteByMailAndAnOpportunityInTheCrm() {
        understands("12 pallets Warsaw -> Berlin", GOLD_01);
        // 575 km, 7200 kg: BudgetTrans 487.60; GOLD 12 %, fuel 8 % → 590
        replies(RESPOND, "Dear Anna, our price for Warszawa - Berlin is 590.00 EUR, pickup on 2026-10-06.");

        UUID id = start("01-happy-path-gold.eml");
        awaitState(id, QuoteState.FOLLOW_UP);

        QuoteContext ctx = instance(id).getContext();
        assertThat(ctx.policy().auto()).isTrue();
        assertThat(ctx.quote().price()).isEqualByComparingTo("590");
        assertThat(ctx.flags()).doesNotContain(RespondStep.FALLBACK_TEMPLATE);
        assertThat(deliveredQuote(id, "anna.kowalska@polmarket.test").subject()).isEqualTo("Your freight quote");
        assertThat(crmWrites(id))
                .singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK));
    }

    @Test
    void s2_aNewCustomerWithoutWeightIsAskedThenApprovedAndQuotedButNotWrittenToTheCrm() {
        // the reply text only reaches the second prompt, so its rule comes first
        understands(
                "weight is 2000 kg",
                extraction("Łódź", "Praha", "2000", 4, "bicycle parts", LocalDate.of(2026, 10, 6), "pl", false));
        understands(
                "Lodz -> Praga",
                extraction("Łódź", "Praha", null, 4, "bicycle parts", LocalDate.of(2026, 10, 6), "pl", false));
        // 560 km, 2000 kg: BudgetTrans 212.80; new customer 22 %, fuel 8 % → 280
        replies(RESPOND, "Dzień dobry, cena transportu Łódź - Praha wynosi 280,00 EUR.");

        UUID id = start("03-new-customer.eml");
        awaitState(id, QuoteState.AWAIT_REPLY);
        assertThat(outbox.findByDedupeKey(id + ":CLARIFICATION-1")).isPresent();

        engine.signal(id, new Signal.CustomerReplied("The weight is 2000 kg."));
        awaitState(id, QuoteState.AWAIT_APPROVAL);
        assertThat(instance(id).getContext().policy().reasons()).containsExactly("flag:NEW_CUSTOMER");

        approve(id);
        awaitState(id, QuoteState.FOLLOW_UP);

        QuoteContext ctx = instance(id).getContext();
        assertThat(ctx.quote().price()).isEqualByComparingTo("280");
        assertThat(ctx.flags()).contains(RecordStep.CRM_SKIPPED_NEW_CUSTOMER);
        assertThat(crmWrites(id)).isEmpty();
        deliveredQuote(id, "tomasz.nowak@nowafirma.test");
    }

    @Test
    void s3_aPromptInjectionIsFlaggedGoesToApprovalAndTheReplyHasNoDiscountAndNoEmailText() {
        understands(
                "Valencia -> Lyon",
                extraction("Valencia", "Lyon", "3100", 6, "shoes", LocalDate.of(2026, 10, 13), "en", true));
        // 880 km, 3100 kg: BudgetTrans 421.52; STANDARD 18 %, fuel 8 % → 537
        replies(RESPOND, "Hello, our price for Valencia - Lyon is 537.00 EUR, pickup on 2026-10-13.");

        UUID id = start("07-prompt-injection.eml");
        awaitState(id, QuoteState.AWAIT_APPROVAL);
        assertThat(instance(id).getContext().flags()).contains(Guards.SUSPICIOUS_INSTRUCTIONS);
        assertThat(instance(id).getContext().policy().reasons())
                .containsExactly("flag:" + Guards.SUSPICIOUS_INSTRUCTIONS);

        approve(id);
        awaitState(id, QuoteState.FOLLOW_UP);

        assertThat(instance(id).getContext().quote().price()).isEqualByComparingTo("537");
        assertThat(quoteMail(id).getPayload().body())
                .contains("537.00 EUR")
                .doesNotContainIgnoringCase("discount")
                .doesNotContain("90");
        assertThat(model.prompts())
                .filteredOn(p -> p.getContents().contains(RESPOND))
                .singleElement()
                .satisfies(p -> assertThat(p.getContents()).doesNotContain("SYSTEM NOTE", "pre-approved", "90%"));
        assertThat(crmWrites(id))
                .singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK));
        deliveredQuote(id, "lucia.garcia@iberiaretail.test");
    }

    @Test
    void s4_aModelThatInventsThePriceIsCaughtTwiceAndTheTemplateGoesOut() {
        understands("12 pallets Warsaw -> Berlin", GOLD_01);
        replies(STRICT, "Price: 550.00 EUR, a special 7% discount for you.");
        replies(RESPOND, "Great news: only 499.00 EUR!");

        UUID id = start("01-happy-path-gold.eml");
        awaitState(id, QuoteState.FOLLOW_UP);

        assertThat(instance(id).getContext().flags()).contains(RespondStep.FALLBACK_TEMPLATE);
        assertThat(quoteMail(id).getPayload().body())
                .contains("Price: 590.00 EUR")
                .doesNotContain("499", "550", "%");
        assertThat(deliveredQuote(id, "anna.kowalska@polmarket.test").subject())
                .isEqualTo("Your freight quote: Warszawa - Berlin");
    }

    @Test
    void s5_aCustomerWhoStaysSilentGetsOneReminderAndTheInstanceCloses() {
        understands("12 pallets Warsaw -> Berlin", GOLD_01);
        replies(RESPOND, "Our price is 590.00 EUR.");

        UUID id = start("01-happy-path-gold.eml");
        awaitState(id, QuoteState.FOLLOW_UP);
        deliveredQuote(id, "anna.kowalska@polmarket.test");

        engine.onTimeout(id, QuoteState.FOLLOW_UP); // 72 h later
        assertThat(instance(id).getContext().flags()).contains(FollowUpHandler.FOLLOW_UP_SENT);
        OutboxEntry reminder = outbox.findByDedupeKey(id + ":FOLLOW-UP-1").orElseThrow();
        assertThat(reminder.getPayload().inReplyTo()).isEqualTo("<" + id + ".quote@nordline.test>");
        assertThat(reminder.getPayload().body()).contains("590.00 EUR");
        relay.dispatchOnce();

        engine.onTimeout(id, QuoteState.FOLLOW_UP); // another 72 h

        assertThat(instance(id).getState()).isEqualTo(QuoteState.CLOSED);
        assertThat(instance(id).getContext().closeReason()).isEqualTo("NO_RESPONSE");
        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(id)).hasSize(2);
    }
}
