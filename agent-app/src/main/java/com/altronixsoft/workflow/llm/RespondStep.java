package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.outbox.MailTemplates;
import com.altronixsoft.workflow.outbox.OutboxService;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteFacts;
import com.altronixsoft.workflow.quote.QuoteState;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the reply to an approved quote. The model drafts it from {@link QuoteFacts} only (no cost, no margin, no
 * email text); {@link NumericGuard} checks every amount and date. A failed draft gets one strict retry with the
 * violations named; if that fails too, the reply is the fixed template and the instance is flagged. The mail goes
 * to the outbox with a Message-ID derived from the instance; this step sends nothing itself.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class RespondStep implements Step {

    public static final String FALLBACK_TEMPLATE = "RESPONSE_FALLBACK_TEMPLATE";
    static final String PROMPT = "respond-v1";
    static final String STRICT_PROMPT = "respond-strict";

    private static final Logger log = LoggerFactory.getLogger(RespondStep.class);

    private final ChatClient chat;
    private final PromptLoader prompts;
    private final NumericGuard guard;
    private final MailTemplates templates;
    private final OutboxService outbox;
    private final JsonMapper json;
    private final MeterRegistry meters;

    RespondStep(
            ChatClient chat,
            PromptLoader prompts,
            NumericGuard guard,
            MailTemplates templates,
            OutboxService outbox,
            JsonMapper json,
            MeterRegistry meters) {
        this.chat = chat;
        this.prompts = prompts;
        this.guard = guard;
        this.templates = templates;
        this.outbox = outbox;
        this.json = json;
        this.meters = meters;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.APPROVED;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        QuoteFacts facts = QuoteFacts.from(ctx);
        QuoteContext result = ctx;

        ReplyDraft reply = null;
        ReplyDraft first = draft(PROMPT, "Facts:\n" + json.writeValueAsString(facts));
        List<String> problems = first == null ? List.of("the draft was empty") : guard.check(first.body(), facts);
        drafted("v1", problems.isEmpty());
        if (problems.isEmpty()) {
            reply = first;
        } else {
            ReplyDraft strict = draft(
                    STRICT_PROMPT,
                    "Problems in the previous draft:\n- " + String.join("\n- ", problems) + "\n\nFacts:\n"
                            + json.writeValueAsString(facts));
            boolean passed = strict != null && guard.check(strict.body(), facts).isEmpty();
            drafted("strict", passed);
            if (passed) {
                reply = strict;
            }
        }
        if (reply == null) {
            meters.counter("response.fallback").increment();
            MailTemplates.Mail mail = templates.render("quote", facts.language(), facts.placeholders());
            reply = new ReplyDraft(mail.subject(), mail.body());
            result = result.withFlag(FALLBACK_TEMPLATE);
        }

        String messageId = outbox.enqueueEmail(
                instanceId,
                "QUOTE",
                ctx.email().from(),
                reply.subject(),
                reply.body(),
                ctx.email().messageId());
        return new StepResult.Next(QuoteState.RESPONDED, result.withOutboundMessageId(messageId));
    }

    /** {@code response.drafts}: how many drafts per rung passed or failed the guard (a failed model counts as failed). */
    private void drafted(String rung, boolean passed) {
        meters.counter("response.drafts", "rung", rung, "result", passed ? "passed" : "rejected")
                .increment();
    }

    /** The model's draft, or null if it failed or came back without a subject or body. */
    private ReplyDraft draft(String promptName, String facts) {
        PromptLoader.Prompt prompt = prompts.load(promptName);
        try {
            ReplyDraft draft = chat.prompt()
                    .system(prompt.text())
                    .user(u -> u.text("{facts}").param("facts", facts))
                    .advisors(a -> a.param(AuditAdvisor.PROMPT_VERSION, prompt.version()))
                    .call()
                    .entity(ReplyDraft.class);
            if (draft == null || isBlank(draft.subject()) || isBlank(draft.body())) {
                return null;
            }
            return draft;
        } catch (RuntimeException e) {
            log.warn("Reply draft {} failed: {}", prompt.version(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
