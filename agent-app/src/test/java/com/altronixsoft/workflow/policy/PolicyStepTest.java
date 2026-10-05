package com.altronixsoft.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.approval.ApprovalActions;
import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyStepTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000007");

    private final ApprovalTokens tokens = new ApprovalTokens("test-approval-secret-0123456789abcdef", CLOCK);
    private final PolicyStep step =
            new PolicyStep(new PolicyEngine(PolicyEngineTest.RULES), PolicyEngineTest.RULES, tokens, CLOCK);

    private static QuoteContext priced(String price) {
        BigDecimal cost = new BigDecimal("1000.00");
        BigDecimal p = new BigDecimal(price);
        return QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml"))
                .withQuote(new Quote("EuroLine", cost, p, Quote.marginPct(cost, p), "EUR", null, null));
    }

    @Test
    void anAllowedQuoteIsApprovedByThePolicyWithATokenForExactlyThatPrice() {
        StepResult result = step.execute(INSTANCE, priced("1210.00"));

        assertThat(result).isInstanceOfSatisfying(StepResult.Next.class, next -> {
            assertThat(next.next()).isEqualTo(QuoteState.APPROVED);
            assertThat(next.ctx().policy().auto()).isTrue();
            ApprovalClaims claims = tokens.verify(
                    next.ctx().approvalToken(), ApprovalActions.CREATE_OPPORTUNITY, new BigDecimal("1210.00"));
            assertThat(claims.instanceId()).isEqualTo(INSTANCE);
            assertThat(claims.approvedBy()).isEqualTo("system:policy");
            assertThat(claims.maxAmount()).isEqualByComparingTo("1210.00");
            assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        });
    }

    @Test
    void aBlockedQuoteWaitsForAPersonWithTheReasonsAndNoToken() {
        StepResult result = step.execute(INSTANCE, priced("1210.00").withFlag("CREDIT_HOLD"));

        assertThat(result).isInstanceOfSatisfying(StepResult.Wait.class, wait -> {
            assertThat(wait.waitState()).isEqualTo(QuoteState.AWAIT_APPROVAL);
            assertThat(wait.timeout()).isEqualTo(Duration.ofHours(24));
            assertThat(wait.ctx().policy().auto()).isFalse();
            assertThat(wait.ctx().policy().reasons()).containsExactly("flag:CREDIT_HOLD");
            assertThat(wait.ctx().approvalToken()).isNull();
        });
    }
}
