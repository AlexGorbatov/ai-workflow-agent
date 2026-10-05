package com.altronixsoft.workflow.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The gateway against the real mock CRM (MCP) and mock rates (REST), audited into PostgreSQL. */
@IntegrationTest
@Import(MockApps.class)
class ToolGatewayIT {

    @Autowired
    ToolGateway gateway;

    @Autowired
    ToolRegistry registry;

    @Autowired
    ToolCallRepository calls;

    @Autowired
    JsonMapper json;

    private final ApprovalTokens tokens = new ApprovalTokens(MockApps.APPROVAL_SECRET, Clock.systemUTC());

    private String token(UUID instanceId, BigDecimal maxAmount) {
        return tokens.issue(new ApprovalClaims(
                instanceId,
                "createOpportunity",
                maxAmount,
                "olena",
                Clock.systemUTC().instant().plus(Duration.ofHours(1))));
    }

    @Test
    void theRegistryHasTheCrmToolsAndGetRates() {
        assertThat(registry.names())
                .contains("findCustomersByEmail", "getCreditStatus", "createOpportunity", "getRates");
    }

    @Test
    void aCrmReadComesBackAsJsonAndIsAudited() {
        UUID instanceId = UUID.randomUUID();

        String result = gateway.call(
                "findCustomersByEmail",
                Map.of("email", "anna.kowalska@polmarket.test"),
                CallContext.of(instanceId, QuoteState.UNDERSTOOD));

        JsonNode customers = json.readTree(result);
        assertThat(customers).hasSize(1);
        assertThat(customers.get(0).get("id").asString()).isEqualTo("C-1001");
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.getStatus()).isEqualTo(ToolCallStatus.OK);
                    assertThat(call.getKind()).isEqualTo(ToolKind.READ);
                    assertThat(call.getIdempotencyKey()).isEqualTo(instanceId + ":findCustomersByEmail");
                    assertThat(call.getArgs()).containsEntry("email", "anna.kowalska@polmarket.test");
                    assertThat(call.getResult()).contains("C-1001");
                });
    }

    @Test
    void ratesComeFromTheRatesServiceAndAnUnservedLaneIsEmpty() {
        CallContext cc = CallContext.of(UUID.randomUUID(), QuoteState.ENRICHED);

        JsonNode rates = json.readTree(
                gateway.call("getRates", Map.of("origin", "Warszawa", "destination", "Berlin", "weightKg", 12000), cc));
        String none = gateway.call("getRates", Map.of("origin", "Oslo", "destination", "Lisboa", "weightKg", 100), cc);

        assertThat(rates).hasSize(3);
        assertThat(rates.get(0).has("carrier")).isTrue();
        assertThat(rates.get(0).has("transitDays")).isTrue();
        assertThat(json.readTree(none)).isEmpty();
    }

    @Test
    void aRefusalIsAuditedInTheDatabase() {
        UUID instanceId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.call(
                        "getRates",
                        Map.of("origin", "Warszawa", "destination", "Berlin", "weightKg", 1),
                        CallContext.of(instanceId, QuoteState.UNDERSTOOD)))
                .isInstanceOf(ToolDenied.class);

        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.getStatus()).isEqualTo(ToolCallStatus.DENIED);
                    assertThat(call.getError()).contains("not allowed in state UNDERSTOOD");
                });
    }

    @Test
    void aWriteWithAValidTokenCreatesOneOpportunityPerKeyAndTheTokenIsNotStored() {
        UUID instanceId = UUID.randomUUID();
        String token = token(instanceId, new BigDecimal("5000"));
        CallContext cc = new CallContext(instanceId, QuoteState.RESPONDED, token);
        Map<String, Object> args = Map.of(
                "customerId",
                "C-1001",
                "amount",
                new BigDecimal("1210.00"),
                "currency",
                "EUR",
                "idempotencyKey",
                instanceId + ":opportunity");

        String first = gateway.call("createOpportunity", args, cc);
        String again = gateway.call("createOpportunity", args, cc);

        assertThat(json.readTree(again).get("id"))
                .isEqualTo(json.readTree(first).get("id"));
        List<ToolCall> audited = calls.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        assertThat(audited).hasSize(2).allSatisfy(call -> {
            assertThat(call.getStatus()).isEqualTo(ToolCallStatus.OK);
            assertThat(call.getKind()).isEqualTo(ToolKind.WRITE);
            assertThat(call.getIdempotencyKey()).isEqualTo(instanceId + ":opportunity");
            assertThat(json.writeValueAsString(call.getArgs())).doesNotContain(token);
        });
    }

    @Test
    void aWriteAboveWhatTheTokenAllowsIsRefusedByTheCrm() {
        UUID instanceId = UUID.randomUUID();
        CallContext cc = new CallContext(instanceId, QuoteState.RESPONDED, token(instanceId, new BigDecimal("100")));
        Map<String, Object> args = Map.of(
                "customerId",
                "C-1001",
                "amount",
                new BigDecimal("1210.00"),
                "currency",
                "EUR",
                "idempotencyKey",
                instanceId + ":opportunity");

        assertThatThrownBy(() -> gateway.call("createOpportunity", args, cc))
                .isInstanceOfSatisfying(
                        ToolCallFailed.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageContaining("approval token");

        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .singleElement()
                .satisfies(call -> assertThat(call.getStatus()).isEqualTo(ToolCallStatus.ERROR));
    }

    @Test
    void anInvestigatingModelCannotSeeTheWriteTool() {
        assertThat(gateway.readOnlyCallbacks(CallContext.of(UUID.randomUUID(), QuoteState.INVESTIGATING), 5))
                .extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("findCustomersByEmail", "getCreditStatus", "getRates")
                .doesNotContain("createOpportunity");
    }
}
