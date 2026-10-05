package com.altronixsoft.workflow.tools;

import com.altronixsoft.workflow.engine.NonRetryableStepException;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single door to external systems. Every call passes five checks, in this order, and every call is
 * recorded in {@code tool_call}, refusals included:
 *
 * <ol>
 *   <li>the tool has a policy and is allowed in the caller's workflow state;
 *   <li>a WRITE carries an approval token and an {@code idempotencyKey};
 *   <li>the arguments match the tool's JSON schema;
 *   <li>the call finishes within the policy's timeout, or is cancelled;
 *   <li>the outcome is audited.
 * </ol>
 *
 * Refusals are {@link ToolDenied} (nothing was sent); failures after sending are {@link ToolCallFailed}.
 */
@Component
public class ToolGateway {

    /** What a model gets instead of a result once its call budget is spent. */
    public static final String BUDGET_EXCEEDED = "budget exceeded";

    static final String APPROVAL_TOKEN = "approvalToken";
    static final String IDEMPOTENCY_KEY = "idempotencyKey";

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final ToolRegistry registry;
    private final ToolsProperties properties;
    private final ToolAudit audit;
    private final JsonMapper json;
    private final JsonSchemaValidator validator;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ToolGateway(ToolRegistry registry, ToolsProperties properties, ToolAudit audit, JsonMapper json) {
        this.registry = registry;
        this.properties = properties;
        this.audit = audit;
        this.json = json;
        this.validator = new DefaultJsonSchemaValidator(json);
    }

    /**
     * Calls {@code tool} for the instance in {@code cc} and returns its result as the tool wrote it (JSON for
     * the tools we have). For a WRITE, the approval token is taken from {@code cc} and sent as the tool's
     * {@code approvalToken} argument; the caller provides {@code idempotencyKey}.
     */
    public String call(String tool, Map<String, Object> args, CallContext cc) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        ToolPolicy policy = properties.policies().get(tool);
        ToolKind kind = policy == null ? null : policy.kind();
        String key = idempotencyKey(kind, tool, arguments, cc);
        return audit.record(cc, tool, arguments, kind, key, () -> {
            // 1. allowed in this state
            if (policy == null) {
                throw new ToolDenied("No policy for tool " + tool);
            }
            if (!policy.allowedIn().contains(cc.state())) {
                throw new ToolDenied(tool + " is not allowed in state " + cc.state());
            }
            // 2. a write needs an approval token and an idempotency key
            Map<String, Object> sent = arguments;
            if (kind == ToolKind.WRITE) {
                if (isBlank(cc.approvalToken())) {
                    throw new ToolDenied(tool + " is a WRITE and needs an approval token");
                }
                if (isBlank(key)) {
                    throw new ToolDenied(tool + " is a WRITE and needs an " + IDEMPOTENCY_KEY);
                }
                sent = new LinkedHashMap<>(arguments);
                sent.put(APPROVAL_TOKEN, cc.approvalToken());
            }
            ToolCallback callback =
                    registry.find(tool).orElseThrow(() -> new ToolDenied("Tool " + tool + " is not available"));
            // 3. arguments match the schema
            validate(callback.getToolDefinition(), sent);
            // 4. bounded in time; 5. the audit around all of it
            return withTimeout(callback, json.writeValueAsString(sent), policy.timeout());
        });
    }

    /**
     * {@link #call} for a step: a refusal or a failure that a retry cannot fix becomes a {@link
     * NonRetryableStepException}; an outage or a timeout stays a retryable {@link ToolCallFailed}.
     */
    public String callFromStep(String tool, Map<String, Object> args, CallContext cc) {
        try {
            return call(tool, args, cc);
        } catch (ToolDenied e) {
            throw new NonRetryableStepException(e.getMessage(), e);
        } catch (ToolCallFailed e) {
            if (e.retryable()) {
                throw e;
            }
            throw new NonRetryableStepException(e.getMessage(), e);
        }
    }

    /**
     * The READ tools allowed in {@code cc}'s state, for a model to call. Each call goes through {@link #call};
     * after {@code maxCalls} of them (shared by all the returned callbacks) the answer is {@link
     * #BUDGET_EXCEEDED} instead of an exception, so the model can wrap up rather than crash the step.
     */
    public List<ToolCallback> readOnlyCallbacks(CallContext cc, int maxCalls) {
        AtomicInteger used = new AtomicInteger();
        return properties.policies().entrySet().stream()
                .filter(e -> e.getValue().kind() == ToolKind.READ)
                .filter(e -> e.getValue().allowedIn().contains(cc.state()))
                .flatMap(e -> registry.find(e.getKey()).stream())
                .map(callback -> (ToolCallback) new Budgeted(callback.getToolDefinition(), cc, used, maxCalls))
                .toList();
    }

    private void validate(ToolDefinition definition, Map<String, Object> arguments) {
        Map<String, Object> schema = json.readValue(definition.inputSchema(), OBJECT);
        JsonSchemaValidator.ValidationResponse response = validator.validate(schema, arguments);
        if (!response.valid()) {
            throw new ToolDenied("Invalid arguments for " + definition.name() + ": " + response.errorMessage());
        }
    }

    private String withTimeout(ToolCallback callback, String input, Duration timeout) {
        Future<String> running = executor.submit(() -> callback.call(input));
        try {
            return running.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            running.cancel(true);
            throw new ToolCallFailed(
                    callback.getToolDefinition().name() + " timed out after " + timeout.toMillis() + " ms", true, e);
        } catch (InterruptedException e) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolCallFailed(callback.getToolDefinition().name() + " was interrupted", true, e);
        } catch (ExecutionException e) {
            throw failure(callback.getToolDefinition().name(), e.getCause());
        }
    }

    /** What the tool threw, as a {@link ToolCallFailed}; anything unexpected counts as retryable. */
    private static ToolCallFailed failure(String tool, Throwable cause) {
        Throwable unwrapped = cause;
        while (unwrapped instanceof ToolExecutionException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        if (unwrapped instanceof ToolCallFailed failed) {
            return failed;
        }
        return new ToolCallFailed(tool + " failed: " + unwrapped.getMessage(), true, unwrapped);
    }

    /** WRITE: the caller's {@code idempotencyKey}. READ: one key per instance and tool. */
    private static String idempotencyKey(ToolKind kind, String tool, Map<String, Object> args, CallContext cc) {
        if (kind == ToolKind.WRITE) {
            Object key = args.get(IDEMPOTENCY_KEY);
            return key == null ? null : key.toString();
        }
        return cc.instanceId() + ":" + tool;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** A READ tool for a model: same name and schema, but every call goes through the gateway and the budget. */
    private final class Budgeted implements ToolCallback {

        private final ToolDefinition definition;
        private final CallContext cc;
        private final AtomicInteger used;
        private final int maxCalls;

        Budgeted(ToolDefinition definition, CallContext cc, AtomicInteger used, int maxCalls) {
            this.definition = definition;
            this.cc = cc;
            this.used = used;
            this.maxCalls = maxCalls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            if (used.incrementAndGet() > maxCalls) {
                return BUDGET_EXCEEDED;
            }
            Map<String, Object> args = isBlank(toolInput) ? Map.of() : json.readValue(toolInput, OBJECT);
            return ToolGateway.this.call(definition.name(), args, cc);
        }
    }
}
