package com.altronixsoft.workflow.mockcrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "approval.token.secret=" + CrmToolsIT.SECRET)
class CrmToolsIT {

    static final String SECRET = "crm-test-secret-that-is-long-enough-0123456789";

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    CrmStore store;

    private final ApprovalTokens tokens = new ApprovalTokens(SECRET, Clock.systemUTC());
    private McpTestClient client;

    @BeforeEach
    void connect() {
        client = new McpTestClient(port);
    }

    @AfterEach
    void disconnect() {
        client.close();
    }

    private JsonNode parse(String text) {
        return json.readTree(text);
    }

    private String token(String action, String max) {
        return tokens.issue(new ApprovalClaims(
                UUID.randomUUID(),
                action,
                max == null ? null : new BigDecimal(max),
                "user:olena",
                Clock.systemUTC().instant().plus(Duration.ofHours(1))));
    }

    private Map<String, Object> opportunity(String customerId, String amount, String token, String key) {
        return Map.of(
                "customerId", customerId,
                "amount", new BigDecimal(amount),
                "currency", "EUR",
                "approvalToken", token,
                "idempotencyKey", key);
    }

    @Test
    void theThreeToolsAreAdvertisedWithDescriptionsAndSchemas() {
        var tools = client.tools().tools();

        assertThat(tools)
                .extracting(t -> t.name())
                .containsExactlyInAnyOrder("findCustomersByEmail", "getCreditStatus", "createOpportunity");
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.description()).isNotBlank();
            assertThat(t.inputSchema()).isNotNull();
        });
        var create = tools.stream()
                .filter(t -> t.name().equals("createOpportunity"))
                .findFirst()
                .orElseThrow();
        assertThat(create.description()).contains("approvalToken").contains("idempotencyKey");
    }

    @Test
    void aKnownContactIsFoundByExactEmail() {
        JsonNode found = parse(client.call("findCustomersByEmail", Map.of("email", "anna.kowalska@polmarket.test")));

        assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).get("id").asString()).isEqualTo("C-1001");
        assertThat(found.get(0).get("tier").asString()).isEqualTo("GOLD");
        assertThat(found.get(0).get("name").asString()).isEqualTo("PolMarket Sp. z o.o.");
    }

    @Test
    void emailMatchingIgnoresCase() {
        JsonNode found = parse(client.call("findCustomersByEmail", Map.of("email", "Marco.Rossi@AdriaticFoods.test")));

        assertThat(found.get(0).get("id").asString()).isEqualTo("C-1005");
    }

    @Test
    void anUnknownSenderOnAKnownDomainMatchesEveryCustomerOfThatDomain() {
        JsonNode found = parse(client.call("findCustomersByEmail", Map.of("email", "accounting@polmarket.test")));

        Set<String> ids = new java.util.HashSet<>();
        found.forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).containsExactlyInAnyOrder("C-1001", "C-1007");
    }

    @Test
    void aStrangerIsNotFound() {
        assertThat(parse(client.call("findCustomersByEmail", Map.of("email", "someone@nowafirma.test")))
                        .size())
                .isZero();
    }

    @Test
    void creditStatusReportsTheOverdueAmount() {
        JsonNode ok = parse(client.call("getCreditStatus", Map.of("customerId", "C-1001")));
        JsonNode watch = parse(client.call("getCreditStatus", Map.of("customerId", "C-1003")));

        assertThat(ok.get("status").asString()).isEqualTo("OK");
        assertThat(ok.get("overdueAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(watch.get("status").asString()).isEqualTo("WATCH");
        assertThat(watch.get("overdueAmount").decimalValue()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void creditStatusOfAnUnknownCustomerIsAnError() {
        assertThat(client.callExpectingError("getCreditStatus", Map.of("customerId", "C-9999")))
                .contains("C-9999");
    }

    @Test
    void anOpportunityNeedsAValidToken() {
        String key = "no-token-" + UUID.randomUUID();

        String error = client.callExpectingError("createOpportunity", opportunity("C-1001", "1210", "garbage", key));

        assertThat(error).containsIgnoringCase("approval token");
        assertThat(store.opportunityCount(key)).isZero();
    }

    @Test
    void anOpportunityIsRefusedForAnAmountAboveTheApprovedLimit() {
        String key = "too-much-" + UUID.randomUUID();

        String error = client.callExpectingError(
                "createOpportunity", opportunity("C-1001", "1300", token("createOpportunity", "1210"), key));

        assertThat(error).containsIgnoringCase("amount");
        assertThat(store.opportunityCount(key)).isZero();
    }

    @Test
    void aTokenForAnotherActionDoesNotOpenThisOne() {
        String error = client.callExpectingError(
                "createOpportunity",
                opportunity("C-1001", "100", token("sendEmail", "1000"), "wrong-action-" + UUID.randomUUID()));

        assertThat(error).containsIgnoringCase("not for createOpportunity");
    }

    @Test
    void anApprovedOpportunityIsCreated() {
        String key = "ok-" + UUID.randomUUID();

        JsonNode created = parse(client.call(
                "createOpportunity", opportunity("C-1001", "1210.00", token("createOpportunity", "1210"), key)));

        assertThat(created.get("id").asString()).startsWith("OPP-");
        assertThat(created.get("customerId").asString()).isEqualTo("C-1001");
        assertThat(created.get("amount").decimalValue()).isEqualByComparingTo("1210.00");
        assertThat(created.get("currency").asString()).isEqualTo("EUR");
        assertThat(store.opportunityCount(key)).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameOpportunityAndCreatesNoSecondOne() {
        String key = "twice-" + UUID.randomUUID();
        Map<String, Object> args = opportunity("C-1001", "1210", token("createOpportunity", "1210"), key);

        JsonNode first = parse(client.call("createOpportunity", args));
        JsonNode second = parse(client.call("createOpportunity", args));

        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(store.opportunityCount(key)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesWithOneKeyStillCreateOneOpportunity() throws Exception {
        String key = "race-" + UUID.randomUUID();
        Map<String, Object> args = opportunity("C-1001", "500", token("createOpportunity", "500"), key);

        List<Thread> threads = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
                    try (McpTestClient own = new McpTestClient(port)) {
                        own.call("createOpportunity", args);
                    }
                }))
                .collect(Collectors.toList());
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        assertThat(store.opportunityCount(key)).isEqualTo(1);
    }

    @Test
    void anOpportunityForAnUnknownCustomerIsRefused() {
        String error = client.callExpectingError(
                "createOpportunity",
                opportunity("C-9999", "100", token("createOpportunity", "1000"), "ghost-" + UUID.randomUUID()));

        assertThat(error).contains("C-9999");
    }

    @Test
    void aMissingRequiredArgumentIsRefused() {
        client.callExpectingError("findCustomersByEmail", Map.of());
    }
}
