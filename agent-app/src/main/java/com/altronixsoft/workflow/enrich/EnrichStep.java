package com.altronixsoft.workflow.enrich;

import com.altronixsoft.workflow.engine.NonRetryableStepException;
import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.CallContext;
import com.altronixsoft.workflow.tools.ToolCallFailed;
import com.altronixsoft.workflow.tools.ToolDenied;
import com.altronixsoft.workflow.tools.ToolGateway;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the sender in the CRM. No match: a new customer, flagged for a human. One match: that customer, and a
 * credit hold if they owe overdue money. Several (an unknown sender on a shared domain): nobody can tell which,
 * so the instance goes to investigation instead of guessing.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class EnrichStep implements Step {

    public static final String NEW_CUSTOMER = "NEW_CUSTOMER";
    public static final String CREDIT_HOLD = "CREDIT_HOLD";
    public static final String AMBIGUOUS_CUSTOMER = "AMBIGUOUS_CUSTOMER";

    private static final TypeReference<List<CrmCustomer>> CUSTOMERS = new TypeReference<>() {};

    private final ToolGateway gateway;
    private final JsonMapper json;

    EnrichStep(ToolGateway gateway, JsonMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.UNDERSTOOD;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        CallContext cc = CallContext.of(instanceId, handles());
        String sender = ctx.email().from();
        List<CrmCustomer> found = json.readValue(call("findCustomersByEmail", Map.of("email", sender), cc), CUSTOMERS);

        if (found.isEmpty()) {
            return new StepResult.Next(QuoteState.ENRICHED, ctx.withFlag(NEW_CUSTOMER));
        }
        if (found.size() > 1) {
            return new StepResult.Next(QuoteState.INVESTIGATING, ctx.withError(AMBIGUOUS_CUSTOMER));
        }

        CrmCustomer match = found.getFirst();
        QuoteContext enriched = ctx.withCustomer(new Customer(match.id(), match.name(), sender, tier(match.tier())));
        CreditStatus credit =
                json.readValue(call("getCreditStatus", Map.of("customerId", match.id()), cc), CreditStatus.class);
        if (credit.overdueAmount() != null && credit.overdueAmount().signum() > 0) {
            enriched = enriched.withFlag(CREDIT_HOLD);
        }
        return new StepResult.Next(QuoteState.ENRICHED, enriched);
    }

    /** A refusal or a final failure will not change on retry; an outage or a timeout may. */
    private String call(String tool, Map<String, Object> args, CallContext cc) {
        try {
            return gateway.call(tool, args, cc);
        } catch (ToolDenied e) {
            throw new NonRetryableStepException(e.getMessage(), e);
        } catch (ToolCallFailed e) {
            if (e.retryable()) {
                throw e;
            }
            throw new NonRetryableStepException(e.getMessage(), e);
        }
    }

    /** The CRM may know tiers we do not price differently; they are treated as STANDARD. */
    private static CustomerTier tier(String crmTier) {
        if (crmTier == null) {
            return CustomerTier.STANDARD;
        }
        try {
            return CustomerTier.valueOf(crmTier.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CustomerTier.STANDARD;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrmCustomer(String id, String name, String domain, String tier) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreditStatus(String customerId, String status, BigDecimal overdueAmount) {}
}
