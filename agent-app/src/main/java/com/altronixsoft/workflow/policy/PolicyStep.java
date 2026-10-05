package com.altronixsoft.workflow.policy;

import com.altronixsoft.workflow.approval.ApprovalActions;
import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Applies the policy to a priced quote. Allowed: the policy itself approves, with a token for recording exactly
 * this price in the CRM. Not allowed: the quote waits for a person, with the reasons on the instance.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class PolicyStep implements Step {

    public static final String APPROVED_BY = "system:policy";

    private final PolicyEngine engine;
    private final PolicyRules rules;
    private final ApprovalTokens tokens;
    private final Clock clock;

    PolicyStep(PolicyEngine engine, PolicyRules rules, ApprovalTokens tokens, Clock clock) {
        this.engine = engine;
        this.rules = rules;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.PRICED;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        PolicyDecision decision = engine.decide(ctx);
        QuoteContext decided = ctx.withPolicy(decision);
        if (!decision.auto()) {
            return new StepResult.Wait(QuoteState.AWAIT_APPROVAL, rules.approvalSla(), decided);
        }
        String token = tokens.issue(new ApprovalClaims(
                instanceId,
                ApprovalActions.CREATE_OPPORTUNITY,
                ctx.quote().price(),
                APPROVED_BY,
                clock.instant().plus(rules.tokenValidity())));
        return new StepResult.Next(QuoteState.APPROVED, decided.withApprovalToken(token));
    }
}
