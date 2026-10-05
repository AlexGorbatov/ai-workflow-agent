package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.policy.PolicyRules;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.CallContext;
import com.altronixsoft.workflow.tools.ToolGateway;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explains to a person why an instance could not go on by itself. The model gets the error and the structured
 * facts (never the email), may call the read-only tools of this state within a budget, and writes an
 * {@link Investigation}. It advises only: the instance always goes on to wait for a person, and a model that
 * fails leaves an empty investigation and a flag instead of breaking the instance.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class InvestigatorStep implements Step {

    static final String PROMPT = "investigator-v1";
    static final int TOOL_BUDGET = 5;
    public static final String INVESTIGATION_FAILED = "INVESTIGATION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(InvestigatorStep.class);

    private final ChatClient chat;
    private final PromptLoader prompts;
    private final ToolGateway gateway;
    private final FactsRenderer facts;
    private final PolicyRules policy;

    InvestigatorStep(
            ChatClient chat, PromptLoader prompts, ToolGateway gateway, FactsRenderer facts, PolicyRules policy) {
        this.chat = chat;
        this.prompts = prompts;
        this.gateway = gateway;
        this.facts = facts;
        this.policy = policy;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.INVESTIGATING;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        QuoteContext investigated;
        try {
            investigated = ctx.withInvestigation(investigate(instanceId, ctx));
        } catch (RuntimeException e) {
            log.warn(
                    "Investigation failed for instance {}: {}",
                    instanceId,
                    e.getClass().getSimpleName());
            investigated = ctx.withInvestigation(null).withFlag(INVESTIGATION_FAILED);
        }
        return new StepResult.Wait(QuoteState.AWAIT_APPROVAL, policy.approvalSla(), investigated);
    }

    private Investigation investigate(UUID instanceId, QuoteContext ctx) {
        List<ToolCallback> tools = gateway.readOnlyCallbacks(CallContext.of(instanceId, handles()), TOOL_BUDGET);
        PromptLoader.Prompt prompt = prompts.load(PROMPT);
        Investigation investigation = chat.prompt()
                .system(prompt.text())
                .user(u -> u.text("Error code: {error}\n\nFacts:\n{facts}")
                        .param("error", ctx.error() == null ? "none" : ctx.error())
                        .param("facts", facts.render(ctx)))
                .toolCallbacks(tools)
                .advisors(a -> a.param(AuditAdvisor.PROMPT_VERSION, prompt.version()))
                .call()
                .entity(Investigation.class);
        if (investigation == null || investigation.summary() == null) {
            throw new IllegalStateException("The model returned no usable investigation");
        }
        return investigation;
    }
}
