package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.engine.StepScope;
import com.altronixsoft.workflow.quote.QuoteContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * A short briefing for the person who decides an approval task, written by the model from the reasons and the
 * structured facts (never the email text). Advisory and optional: any failure gives {@code null}, and the
 * approval screen works without it.
 */
@Component
public class ApprovalSummarizer {

    static final String PROMPT = "approval-summary-v1";

    private static final Logger log = LoggerFactory.getLogger(ApprovalSummarizer.class);

    private final ChatClient chat;
    private final PromptLoader prompts;
    private final FactsRenderer facts;

    ApprovalSummarizer(ChatClient chat, PromptLoader prompts, FactsRenderer facts) {
        this.chat = chat;
        this.prompts = prompts;
        this.facts = facts;
    }

    /** The summary, or {@code null} if the model failed or said nothing. */
    public String summarize(UUID instanceId, QuoteContext ctx, List<String> reasons) {
        try {
            // No step runs here; the scope only ties the audit row to the instance.
            String text = StepScope.call(new StepScope.Current(instanceId, null), () -> call(ctx, reasons));
            return text == null || text.isBlank() ? null : text.strip();
        } catch (RuntimeException e) {
            log.warn(
                    "Approval summary failed for instance {}: {}",
                    instanceId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private String call(QuoteContext ctx, List<String> reasons) {
        PromptLoader.Prompt prompt = prompts.load(PROMPT);
        return chat.prompt()
                .system(prompt.text())
                .user(u -> u.text("Reasons: {reasons}\n\nFacts:\n{facts}")
                        .param("reasons", reasons.isEmpty() ? "none" : String.join(", ", reasons))
                        .param("facts", facts.render(ctx)))
                .advisors(a -> a.param(AuditAdvisor.PROMPT_VERSION, prompt.version()))
                .call()
                .content();
    }
}
