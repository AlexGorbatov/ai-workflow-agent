package com.altronixsoft.workflow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MailpitTestClient;
import com.altronixsoft.workflow.engine.ScriptedSteps;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;

@IntegrationTest
@Import(ScriptedSteps.class)
class EmailPollerIT {

    @Autowired
    EmailPoller poller;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    EmailThreads threads;

    @Autowired
    JavaMailSender sender;

    @Value("${workflow.mail.mailpit-url}")
    String mailpitUrl;

    private MailpitTestClient mailpit;

    @BeforeEach
    void setUp() {
        ScriptedSteps.reset();
        // Understand, as far as intake is concerned: wait for the customer until a reply is in.
        ScriptedSteps.on(
                QuoteState.RECEIVED,
                ctx -> ctx.replies().isEmpty()
                        ? new StepResult.Wait(QuoteState.AWAIT_REPLY, Duration.ofHours(1), ctx)
                        : new StepResult.Next(QuoteState.UNDERSTOOD, ctx));
        mailpit = new MailpitTestClient(mailpitUrl, sender);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private WorkflowInstance instanceFor(String messageId) {
        return instances
                .findByWorkflowTypeAndBusinessKey(WorkflowEngine.WORKFLOW_TYPE, messageId)
                .orElseThrow();
    }

    private void awaitState(String messageId, QuoteState expected) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> instanceFor(messageId).getState() == expected);
    }

    @Test
    void aNewMailStartsAnInstanceCarryingTheMail() {
        String id = mailpit.sendSample("01-happy-path-gold.eml", suffix());

        poller.pollOnce();

        WorkflowInstance instance = instanceFor(id);
        QuoteContext ctx = instance.getContext();
        assertThat(ctx.email().messageId()).isEqualTo(id);
        assertThat(ctx.email().from()).isEqualTo("anna.kowalska@polmarket.test");
        assertThat(ctx.email().subject()).isEqualTo("Quote request: 12 pallets Warsaw -> Berlin");
        assertThat(ctx.email().body()).contains("12 Euro pallets").contains("7,200 kg");
        assertThat(ctx.email().inReplyTo()).isNull();
        assertThat(ctx.email().receivedAt()).isNotNull();
        assertThat(threads.findInstance(id)).contains(instance.getId());
    }

    @Test
    void headersAndNonAsciiTextAreReadCorrectly() {
        String id = mailpit.sendSample("11-ukrainian-full.eml", suffix());

        poller.pollOnce();

        QuoteContext ctx = instanceFor(id).getContext();
        assertThat(ctx.email().subject()).isEqualTo("Прорахунок доставки Київ – Вроцлав");
        assertThat(ctx.email().body()).contains("8 євро-палет").contains("3 600 кг");
    }

    @Test
    void theSameMailSeenTwiceIsProcessedOnce() {
        String id = mailpit.sendSample("03-new-customer.eml", suffix());

        int first = poller.pollOnce();
        int second = poller.pollOnce();

        assertThat(first).isGreaterThanOrEqualTo(1);
        assertThat(second).isZero();
        assertThat(instanceFor(id)).isNotNull();
    }

    @Test
    void aReplyContinuesTheSameInstanceInsteadOfStartingANewOne() {
        String suffix = suffix();
        String requestId = mailpit.sendSample("04-missing-fields.eml", suffix);
        poller.pollOnce();
        awaitState(requestId, QuoteState.AWAIT_REPLY);
        UUID instanceId = instanceFor(requestId).getId();

        String replyId = mailpit.sendSample("05-clarification-reply.eml", suffix);
        poller.pollOnce();

        awaitState(requestId, QuoteState.CLOSED);
        QuoteContext ctx = instanceFor(requestId).getContext();
        assertThat(ctx.replies()).hasSize(1);
        assertThat(ctx.replies().get(0)).contains("8 pallets, 4,800 kg");
        assertThat(threads.findInstance(replyId)).contains(instanceId);
        assertThat(instances.findByWorkflowTypeAndBusinessKey(WorkflowEngine.WORKFLOW_TYPE, replyId))
                .isEmpty();
    }

    @Test
    void aReplyToOurOwnOutgoingMailFindsTheInstanceToo() {
        String requestId = mailpit.sendSample("04-missing-fields.eml", suffix());
        poller.pollOnce();
        awaitState(requestId, QuoteState.AWAIT_REPLY);
        UUID instanceId = instanceFor(requestId).getId();
        String ourClarification = "<" + instanceId + ".clarification@nordline.test>";
        threads.saveOut(ourClarification, instanceId);

        String replyId = mailpit.sendPlain(
                "marco.rossi@adriaticfoods.test",
                "quotes@nordline.test",
                "Re: your question",
                "6 pallets, 3000 kg, Monday",
                ourClarification);
        poller.pollOnce();

        awaitState(requestId, QuoteState.CLOSED);
        assertThat(instanceFor(requestId).getContext().replies()).containsExactly("6 pallets, 3000 kg, Monday");
        assertThat(threads.findInstance(replyId)).contains(instanceId);
    }

    @Test
    void mailForSomeoneElseIsNotRead() {
        String stranger = mailpit.sendPlain(
                "someone@customer.test",
                "other-" + suffix() + "@nordline.test",
                "Not for the quotes inbox",
                "hi",
                null);

        poller.pollOnce();

        assertThat(threads.known(stranger)).isFalse();
        assertThat(instances.findByWorkflowTypeAndBusinessKey(WorkflowEngine.WORKFLOW_TYPE, stranger))
                .isEmpty();
    }

    @Test
    void anInstanceThatExistsWithoutItsThreadRowIsRecoveredNotDuplicated() {
        // What a crash between "instance started" and "mail recorded" leaves behind.
        String id = mailpit.sendSample("09-not-a-request.eml", suffix());
        UUID existing = engine.start(
                        id,
                        QuoteContext.of(new com.altronixsoft.workflow.quote.InboundEmail(
                                id, null, "accounting@polmarket.test", "Invoice", "body", java.time.Instant.EPOCH)))
                .orElseThrow();
        assertThat(threads.known(id)).isFalse();

        int processed = poller.pollOnce();

        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(threads.findInstance(id)).contains(existing);
        assertThat(poller.pollOnce()).isZero();
    }

    @Test
    void aReplyToAnInstanceThatIsNotWaitingIsRecordedAndDoesNotBreakThePoll() {
        String suffix = suffix();
        String requestId = mailpit.sendSample("04-missing-fields.eml", suffix);
        poller.pollOnce();
        awaitState(requestId, QuoteState.AWAIT_REPLY);
        mailpit.sendSample("05-clarification-reply.eml", suffix);
        poller.pollOnce();
        awaitState(requestId, QuoteState.CLOSED);

        String late = mailpit.sendPlain(
                "marco.rossi@adriaticfoods.test", "quotes@nordline.test", "Re: again", "one more thing", requestId);
        int processed = poller.pollOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(threads.known(late)).isTrue();
        assertThat(instanceFor(requestId).getState()).isEqualTo(QuoteState.CLOSED);
    }
}
