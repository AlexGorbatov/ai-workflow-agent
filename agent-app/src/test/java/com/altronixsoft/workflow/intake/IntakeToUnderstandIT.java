package com.altronixsoft.workflow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MailpitTestClient;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.LlmCall;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.llm.RespondStep;
import com.altronixsoft.workflow.outbox.OutboxRelay;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end: mail in, the real Understand step, a clarification mail out, the reply, then the real Enrich,
 * Price, Policy, Respond and Record steps against the mock CRM and rates, until the quote waits for an answer.
 */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
@Import(MockApps.class)
class IntakeToUnderstandIT {

    @Autowired
    EmailPoller poller;

    @Autowired
    OutboxRelay relay;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    LlmCallRepository llmCalls;

    @Autowired
    StubChatModel model;

    @Autowired
    JsonMapper json;

    @Autowired
    JavaMailSender sender;

    @Value("${workflow.mail.mailpit-url}")
    String mailpitUrl;

    private WorkflowInstance instance(String messageId) {
        return instances
                .findByWorkflowTypeAndBusinessKey(WorkflowEngine.WORKFLOW_TYPE, messageId)
                .orElseThrow();
    }

    @Test
    void anIncompleteRequestIsAnsweredWithAQuestionAndContinuesWhenTheCustomerReplies() {
        model.reset();
        // The reply text appears only in the second prompt, so its rule must come first.
        model.whenPromptContains("8 pallets, 4,800 kg")
                .replyWith(json.writeValueAsString(new Extraction(
                        Intent.QUOTE_REQUEST,
                        "Milano",
                        "Lyon",
                        new BigDecimal("4800"),
                        8,
                        "canned tomatoes",
                        LocalDate.of(2026, 10, 8),
                        "en",
                        List.of(),
                        0.93,
                        false)));
        model.whenPromptContains("Pallets Milan -> Lyon")
                .replyWith(json.writeValueAsString(new Extraction(
                        Intent.QUOTE_REQUEST,
                        "Milano",
                        "Lyon",
                        null,
                        null,
                        "canned tomatoes",
                        null,
                        "en",
                        List.of("pallets", "weightKg", "pickupDate"),
                        0.9,
                        false)));
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, sender);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        String requestId = mailpit.sendSample("04-missing-fields.eml", suffix);
        poller.pollOnce();
        await().atMost(10, TimeUnit.SECONDS).until(() -> instance(requestId).getState() == QuoteState.AWAIT_REPLY);
        UUID instanceId = instance(requestId).getId();

        relay.dispatchOnce();
        List<MailpitTestClient.Summary> toCustomer = mailpit.messagesTo("marco.rossi@adriaticfoods.test");
        String clarificationId = "<" + instanceId + ".clarification-1@nordline.test>";
        assertThat(toCustomer).extracting(m -> "<" + m.messageId() + ">").contains(clarificationId);

        mailpit.sendSample("05-clarification-reply.eml", suffix);
        poller.pollOnce();

        await().atMost(10, TimeUnit.SECONDS).until(() -> instance(requestId).getState() == QuoteState.FOLLOW_UP);
        QuoteContext ctx = instance(requestId).getContext();
        assertThat(ctx.replies()).hasSize(1);
        assertThat(ctx.request().pallets()).isEqualTo(8);
        assertThat(ctx.request().weightKg()).isEqualByComparingTo("4800");
        assertThat(ctx.request().pickupDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(ctx.customer().id()).isEqualTo("C-1005");
        // 440 km, 4800 kg: BudgetTrans 278.08; STANDARD: 278.08 × 1.18 × 1.08 = 354.39 → 354, approved by the policy
        assertThat(ctx.quote().price()).isEqualByComparingTo("354");
        assertThat(ctx.policy().auto()).isTrue();
        assertThat(ctx.approvalToken()).isNotBlank();
        // the stub model writes no usable reply, so the template went out
        assertThat(ctx.flags()).contains(RespondStep.FALLBACK_TEMPLATE);
        assertThat(ctx.outboundMessageId()).isEqualTo("<" + instanceId + ".quote@nordline.test>");

        // both model calls are on record, tied to the instance, with the prompt version
        List<LlmCall> audited = llmCalls.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        assertThat(audited)
                .extracting(LlmCall::getPromptVersion)
                .containsExactly("understand-v1", "understand-v1", "respond-v1", "respond-strict-v1");
        assertThat(audited).allSatisfy(c -> assertThat(c.getStepExecutionId()).isNotNull());
    }
}
