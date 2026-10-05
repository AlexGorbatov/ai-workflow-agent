package com.altronixsoft.workflow.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The gateway's checks, one row each, against fake tools: no network, no database. */
class ToolGatewayTest {

    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private static final String LOOKUP_SCHEMA = """
            {"type":"object","properties":{"email":{"type":"string"}},"required":["email"],
             "additionalProperties":false}""";
    private static final String WRITE_SCHEMA = """
            {"type":"object","properties":{"amount":{"type":"number"},"approvalToken":{"type":"string"},
             "idempotencyKey":{"type":"string"}},"required":["amount","approvalToken","idempotencyKey"],
             "additionalProperties":false}""";
    private static final String EMPTY_SCHEMA = """
            {"type":"object","properties":{}}""";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<ToolCall> audited = new CopyOnWriteArrayList<>();
    private final FakeTool lookup = new FakeTool("lookup", LOOKUP_SCHEMA, input -> "[]");
    private final FakeTool write = new FakeTool("write", WRITE_SCHEMA, input -> "{\"id\":\"OPP-1\"}");
    private final CountDownLatch slowInterrupted = new CountDownLatch(1);
    private final FakeTool slow = new FakeTool("slow", EMPTY_SCHEMA, input -> {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            slowInterrupted.countDown();
        }
        return "too late";
    });
    private final FakeTool refusing = new FakeTool("refusing", EMPTY_SCHEMA, input -> {
        throw new ToolCallFailed("Unknown customer C-0", false);
    });

    private ToolGateway gateway;

    @BeforeEach
    void setUp() {
        ToolCallRepository repository = mock(ToolCallRepository.class);
        when(repository.save(any())).thenAnswer(call -> {
            audited.add(call.getArgument(0));
            return call.getArgument(0);
        });
        ToolAudit audit = new ToolAudit(repository, Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC));
        ToolsProperties properties = new ToolsProperties(
                null,
                null,
                null,
                null,
                Map.of(
                        "lookup",
                        read(QuoteState.UNDERSTOOD, QuoteState.INVESTIGATING),
                        "slow",
                        new ToolPolicy(ToolKind.READ, Set.of(QuoteState.UNDERSTOOD), Duration.ofMillis(200)),
                        "refusing",
                        read(QuoteState.UNDERSTOOD),
                        "write",
                        new ToolPolicy(ToolKind.WRITE, Set.of(QuoteState.RESPONDED, QuoteState.INVESTIGATING), null)));
        ToolRegistry registry = ToolRegistry.of(Map.of(
                "lookup", lookup,
                "write", write,
                "slow", slow,
                "refusing", refusing,
                "unpoliced", new FakeTool("unpoliced", EMPTY_SCHEMA, input -> "{}")));
        gateway = new ToolGateway(registry, properties, audit, json);
    }

    @AfterEach
    void tearDown() {
        gateway.shutdown();
    }

    private static ToolPolicy read(QuoteState... states) {
        return new ToolPolicy(ToolKind.READ, Set.of(states), null);
    }

    private static CallContext in(QuoteState state) {
        return CallContext.of(INSTANCE, state);
    }

    private ToolCall onlyAuditedCall() {
        assertThat(audited).hasSize(1);
        return audited.getFirst();
    }

    @Test
    void aToolIsRefusedInAStateItIsNotAllowedIn() {
        assertThatThrownBy(() -> gateway.call("lookup", Map.of("email", "a@b.test"), in(QuoteState.PRICED)))
                .isInstanceOf(ToolDenied.class)
                .hasMessageContaining("not allowed in state PRICED");

        assertThat(lookup.inputs).isEmpty();
        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.DENIED);
        assertThat(call.getKind()).isEqualTo(ToolKind.READ);
        assertThat(call.getInstanceId()).isEqualTo(INSTANCE);
    }

    @Test
    void aToolWithoutAPolicyIsRefusedEvenIfItExists() {
        assertThatThrownBy(() -> gateway.call("unpoliced", Map.of(), in(QuoteState.UNDERSTOOD)))
                .isInstanceOf(ToolDenied.class)
                .hasMessageContaining("No policy");

        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.DENIED);
        assertThat(call.getKind()).isNull();
    }

    @Test
    void aWriteWithoutAnApprovalTokenIsRefused() {
        Map<String, Object> args = Map.of("amount", 100, "idempotencyKey", INSTANCE + ":opportunity");

        assertThatThrownBy(() -> gateway.call("write", args, in(QuoteState.RESPONDED)))
                .isInstanceOf(ToolDenied.class)
                .hasMessageContaining("approval token");

        assertThat(write.inputs).isEmpty();
        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.DENIED);
        assertThat(call.getKind()).isEqualTo(ToolKind.WRITE);
        assertThat(call.getIdempotencyKey()).isEqualTo(INSTANCE + ":opportunity");
    }

    @Test
    void aWriteWithoutAnIdempotencyKeyIsRefused() {
        CallContext cc = new CallContext(INSTANCE, QuoteState.RESPONDED, "token");

        assertThatThrownBy(() -> gateway.call("write", Map.of("amount", 100), cc))
                .isInstanceOf(ToolDenied.class)
                .hasMessageContaining("idempotencyKey");

        assertThat(write.inputs).isEmpty();
        assertThat(onlyAuditedCall().getStatus()).isEqualTo(ToolCallStatus.DENIED);
    }

    @Test
    void aWriteGetsTheTokenFromTheContextAndTheAuditNeverSeesIt() {
        CallContext cc = new CallContext(INSTANCE, QuoteState.RESPONDED, "signed-token");
        Map<String, Object> args =
                Map.of("amount", 100, "idempotencyKey", "k-1", "approvalToken", "whatever-the-caller-put-here");

        String result = gateway.call("write", args, cc);

        assertThat(result).isEqualTo("{\"id\":\"OPP-1\"}");
        assertThat(write.inputs)
                .singleElement()
                .satisfies(sent -> assertThat(sent)
                        .containsEntry("approvalToken", "signed-token")
                        .containsEntry("idempotencyKey", "k-1"));
        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.OK);
        assertThat(call.getIdempotencyKey()).isEqualTo("k-1");
        assertThat(call.getArgs()).containsEntry("approvalToken", "***");
    }

    @Test
    void argumentsThatDoNotMatchTheSchemaAreRefusedBeforeTheToolIsCalled() {
        assertThatThrownBy(() -> gateway.call("lookup", Map.of(), in(QuoteState.UNDERSTOOD)))
                .isInstanceOf(ToolDenied.class)
                .hasMessageContaining("Invalid arguments")
                .hasMessageContaining("email");
        assertThatThrownBy(() -> gateway.call("lookup", Map.of("email", 42), in(QuoteState.UNDERSTOOD)))
                .isInstanceOf(ToolDenied.class);
        assertThatThrownBy(() -> gateway.call(
                        "lookup", Map.of("email", "a@b.test", "discount", "90%"), in(QuoteState.UNDERSTOOD)))
                .isInstanceOf(ToolDenied.class);

        assertThat(lookup.inputs).isEmpty();
        assertThat(audited).hasSize(3).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.DENIED));
    }

    @Test
    void aCallThatTakesLongerThanItsPolicyAllowsIsCancelled() throws InterruptedException {
        long started = System.nanoTime();

        assertThatThrownBy(() -> gateway.call("slow", Map.of(), in(QuoteState.UNDERSTOOD)))
                .isInstanceOfSatisfying(
                        ToolCallFailed.class, e -> assertThat(e.retryable()).isTrue())
                .hasCauseInstanceOf(TimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(slowInterrupted.await(5, TimeUnit.SECONDS))
                .as("the tool's thread is interrupted")
                .isTrue();
        assertThat(onlyAuditedCall().getStatus()).isEqualTo(ToolCallStatus.TIMEOUT);
    }

    @Test
    void aSuccessfulReadIsReturnedAndAuditedWithItsKey() {
        String result = gateway.call("lookup", Map.of("email", "a@b.test"), in(QuoteState.UNDERSTOOD));

        assertThat(result).isEqualTo("[]");
        assertThat(lookup.inputs).containsExactly(Map.of("email", "a@b.test"));
        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.OK);
        assertThat(call.getKind()).isEqualTo(ToolKind.READ);
        assertThat(call.getTool()).isEqualTo("lookup");
        assertThat(call.getIdempotencyKey()).isEqualTo(INSTANCE + ":lookup");
        assertThat(call.getArgs()).containsEntry("email", "a@b.test");
        assertThat(call.getResult()).isEqualTo("[]");
        assertThat(call.getError()).isNull();
    }

    @Test
    void aFailureReportedByTheToolKeepsItsRetryabilityAndIsAudited() {
        assertThatThrownBy(() -> gateway.call("refusing", Map.of(), in(QuoteState.UNDERSTOOD)))
                .isInstanceOfSatisfying(
                        ToolCallFailed.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageContaining("Unknown customer");

        ToolCall call = onlyAuditedCall();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.ERROR);
        assertThat(call.getError()).contains("Unknown customer");
    }

    @Test
    void aModelGetsOnlyTheReadToolsOfItsState() {
        List<ToolCallback> callbacks = gateway.readOnlyCallbacks(in(QuoteState.INVESTIGATING), 5);

        assertThat(callbacks).extracting(c -> c.getToolDefinition().name()).containsExactly("lookup");
    }

    @Test
    void aModelGetsFiveCallsAndThenBudgetExceeded() {
        ToolCallback tool =
                gateway.readOnlyCallbacks(in(QuoteState.INVESTIGATING), 5).getFirst();

        List<String> answers = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            answers.add(tool.call("{\"email\":\"a@b.test\"}"));
        }

        assertThat(answers).containsExactly("[]", "[]", "[]", "[]", "[]", "budget exceeded", "budget exceeded");
        assertThat(lookup.inputs).hasSize(5);
        assertThat(audited).hasSize(5).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK));
    }

    /** A tool that records what it was sent and answers with {@code behavior}. */
    private final class FakeTool implements ToolCallback {

        final List<Map<String, Object>> inputs = new CopyOnWriteArrayList<>();
        private final ToolDefinition definition;
        private final Function<String, String> behavior;

        FakeTool(String name, String schema, Function<String, String> behavior) {
            this.definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema(schema)
                    .build();
            this.behavior = behavior;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            inputs.add(json.readValue(toolInput, new TypeReference<Map<String, Object>>() {}));
            return behavior.apply(toolInput);
        }
    }
}
