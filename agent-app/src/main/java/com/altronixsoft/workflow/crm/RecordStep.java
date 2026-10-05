package com.altronixsoft.workflow.crm;

import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.CallContext;
import com.altronixsoft.workflow.tools.ToolGateway;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Records the sent quote as a CRM opportunity, then waits for the customer. The write goes through the gateway
 * with the instance's approval token (issued for exactly this price) and the key {@code instanceId:opportunity},
 * so running the step again returns the same opportunity instead of a second one. A sender the CRM does not know
 * has nothing to attach the opportunity to: the step only flags that.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class RecordStep implements Step {

    public static final String CRM_SKIPPED_NEW_CUSTOMER = "CRM_SKIPPED_NEW_CUSTOMER";

    private final ToolGateway gateway;
    private final FollowUpProperties followUp;

    RecordStep(ToolGateway gateway, FollowUpProperties followUp) {
        this.gateway = gateway;
        this.followUp = followUp;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.RESPONDED;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        if (ctx.customer() == null) {
            return new StepResult.Wait(QuoteState.FOLLOW_UP, followUp.after(), ctx.withFlag(CRM_SKIPPED_NEW_CUSTOMER));
        }
        Quote quote = ctx.quote();
        gateway.callFromStep(
                "createOpportunity",
                Map.of(
                        "customerId", ctx.customer().id(),
                        "amount", quote.price(),
                        "currency", quote.currency(),
                        "idempotencyKey", instanceId + ":opportunity"),
                new CallContext(instanceId, handles(), ctx.approvalToken()));
        return new StepResult.Wait(QuoteState.FOLLOW_UP, followUp.after(), ctx);
    }
}
