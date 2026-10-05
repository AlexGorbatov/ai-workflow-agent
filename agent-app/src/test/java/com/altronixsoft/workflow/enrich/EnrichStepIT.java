package com.altronixsoft.workflow.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.ToolCall;
import com.altronixsoft.workflow.tools.ToolCallRepository;
import com.altronixsoft.workflow.tools.ToolCallStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** The four ways a sender can look to the CRM, against the real mock CRM over MCP. */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
@Import(MockApps.class)
class EnrichStepIT {

    @Autowired
    EnrichStep step;

    @Autowired
    ToolCallRepository calls;

    private StepResult.Next enrich(UUID instanceId, String sample) {
        StepResult result = step.execute(instanceId, QuoteContext.of(SampleEmails.read(sample)));
        assertThat(result).isInstanceOf(StepResult.Next.class);
        return (StepResult.Next) result;
    }

    @Test
    void aKnownSenderBecomesTheCustomer() {
        UUID instanceId = UUID.randomUUID();

        StepResult.Next next = enrich(instanceId, "01-happy-path-gold.eml");

        assertThat(next.next()).isEqualTo(QuoteState.ENRICHED);
        assertThat(next.ctx().customer())
                .isEqualTo(new Customer(
                        "C-1001", "PolMarket Sp. z o.o.", "anna.kowalska@polmarket.test", CustomerTier.GOLD));
        assertThat(next.ctx().flags()).isEmpty();
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .extracting(ToolCall::getTool)
                .containsExactly("findCustomersByEmail", "getCreditStatus");
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK));
    }

    @Test
    void anUnknownSenderIsANewCustomer() {
        StepResult.Next next = enrich(UUID.randomUUID(), "03-new-customer.eml");

        assertThat(next.next()).isEqualTo(QuoteState.ENRICHED);
        assertThat(next.ctx().customer()).isNull();
        assertThat(next.ctx().flags()).containsExactly(EnrichStep.NEW_CUSTOMER);
    }

    @Test
    void anUnknownSenderOnADomainWithTwoCustomersGoesToInvestigation() {
        UUID instanceId = UUID.randomUUID();

        StepResult.Next next = enrich(instanceId, "09-not-a-request.eml");

        assertThat(next.next()).isEqualTo(QuoteState.INVESTIGATING);
        assertThat(next.ctx().error()).isEqualTo(EnrichStep.AMBIGUOUS_CUSTOMER);
        assertThat(next.ctx().customer()).isNull();
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .extracting(ToolCall::getTool)
                .containsExactly("findCustomersByEmail");
    }

    @Test
    void aCustomerWithOverdueMoneyIsOnCreditHold() {
        StepResult.Next next = enrich(UUID.randomUUID(), "06-dangerous-goods-adr.eml");

        assertThat(next.next()).isEqualTo(QuoteState.ENRICHED);
        assertThat(next.ctx().customer().id()).isEqualTo("C-1003");
        assertThat(next.ctx().flags()).containsExactly(EnrichStep.CREDIT_HOLD);
    }
}
