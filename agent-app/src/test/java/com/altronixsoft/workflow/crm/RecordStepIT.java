package com.altronixsoft.workflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.approval.ApprovalActions;
import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.ToolCall;
import com.altronixsoft.workflow.tools.ToolCallRepository;
import com.altronixsoft.workflow.tools.ToolCallStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The Record step against the real mock CRM, with a token signed like the Policy step signs it. */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
@Import(MockApps.class)
class RecordStepIT {

    private static final BigDecimal PRICE = new BigDecimal("590.00");

    @Autowired
    RecordStep step;

    @Autowired
    ApprovalTokens tokens;

    @Autowired
    ToolCallRepository calls;

    @Autowired
    JsonMapper json;

    private QuoteContext responded(UUID id, Customer customer) {
        BigDecimal cost = new BigDecimal("487.60");
        String token = tokens.issue(new ApprovalClaims(
                id,
                ApprovalActions.CREATE_OPPORTUNITY,
                PRICE,
                "system:policy",
                Clock.systemUTC().instant().plus(Duration.ofDays(7))));
        return QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml"))
                .withCustomer(customer)
                .withQuote(new Quote("BudgetTrans", cost, PRICE, Quote.marginPct(cost, PRICE), "EUR", null, List.of()))
                .withApprovalToken(token);
    }

    @Test
    void theQuoteBecomesOneOpportunityEvenWhenTheStepRunsTwice() {
        UUID id = UUID.randomUUID();
        QuoteContext ctx = responded(
                id, new Customer("C-1001", "PolMarket Sp. z o.o.", "anna.kowalska@polmarket.test", CustomerTier.GOLD));

        StepResult first = step.execute(id, ctx);
        step.execute(id, ctx);

        assertThat(first).isInstanceOfSatisfying(StepResult.Wait.class, wait -> {
            assertThat(wait.waitState()).isEqualTo(QuoteState.FOLLOW_UP);
            assertThat(wait.timeout()).isEqualTo(Duration.ofHours(72));
        });
        List<ToolCall> writes = calls.findByInstanceIdOrderByCreatedAtAsc(id);
        assertThat(writes).hasSize(2).allSatisfy(c -> {
            assertThat(c.getTool()).isEqualTo("createOpportunity");
            assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK);
            assertThat(c.getIdempotencyKey()).isEqualTo(id + ":opportunity");
        });
        assertThat(json.readTree(writes.get(1).getResult()).get("id"))
                .as("the second call returns the first opportunity")
                .isEqualTo(json.readTree(writes.get(0).getResult()).get("id"));
    }

    @Test
    void aNewCustomerIsNotWrittenToTheCrm() {
        UUID id = UUID.randomUUID();

        StepResult result = step.execute(id, responded(id, null));

        assertThat(result).isInstanceOfSatisfying(StepResult.Wait.class, wait -> {
            assertThat(wait.waitState()).isEqualTo(QuoteState.FOLLOW_UP);
            assertThat(wait.ctx().flags()).contains(RecordStep.CRM_SKIPPED_NEW_CUSTOMER);
        });
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(id)).isEmpty();
    }
}
