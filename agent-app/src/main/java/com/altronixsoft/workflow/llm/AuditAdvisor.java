package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.engine.StepScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

/**
 * Writes a {@link LlmCall} row for every model call: timing, model, token usage, cost, prompt and answer, and
 * counts tokens ({@code llm.tokens}) and cost ({@code llm.cost.eur}) per model.
 * The step the call belongs to comes from {@link StepScope}. Failed calls are recorded too, then rethrown.
 * Prompt and completion text go to the table only, never to the log.
 */
public class AuditAdvisor implements CallAdvisor {

    /** Context key a step sets with {@code .advisors(a -> a.param(PROMPT_VERSION, "understand-v1"))}. */
    public static final String PROMPT_VERSION = "promptVersion";

    private final LlmCallRepository calls;
    private final Clock clock;
    private final CostCalculator costs;
    private final MeterRegistry meters;

    public AuditAdvisor(LlmCallRepository calls, Clock clock, CostCalculator costs, MeterRegistry meters) {
        this.calls = calls;
        this.clock = clock;
        this.costs = costs;
        this.meters = meters;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long started = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            record(request, response.chatResponse(), null, started);
            return response;
        } catch (RuntimeException e) {
            record(request, null, e.getClass().getSimpleName() + ": " + e.getMessage(), started);
            throw e;
        }
    }

    private void record(ChatClientRequest request, ChatResponse response, String error, long startedNanos) {
        Optional<StepScope.Current> step = StepScope.current();
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        String model = response == null ? null : response.getMetadata().getModel();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        BigDecimal cost = costs.cost(model, input, output);
        count(model, input, output, cost);
        calls.save(new LlmCall(
                step.map(StepScope.Current::instanceId).orElse((UUID) null),
                step.map(StepScope.Current::stepExecutionId).orElse((UUID) null),
                model,
                promptVersion(request),
                input,
                output,
                cost,
                (System.nanoTime() - startedNanos) / 1_000_000,
                request.prompt().getContents(),
                response == null || response.getResult() == null
                        ? null
                        : response.getResult().getOutput().getText(),
                error,
                clock.instant()));
    }

    /** Tokens and cost per model; tags are the model name and the token type only. */
    private void count(String model, Integer input, Integer output, BigDecimal cost) {
        String name = model == null ? "unknown" : model;
        Counter.builder("llm.tokens")
                .tag("model", name)
                .tag("type", "input")
                .register(meters)
                .increment(input == null ? 0 : input);
        Counter.builder("llm.tokens")
                .tag("model", name)
                .tag("type", "output")
                .register(meters)
                .increment(output == null ? 0 : output);
        // registered even when the model is free, so a local model shows up as 0 instead of missing
        Counter.builder("llm.cost.eur").tag("model", name).register(meters).increment(cost.doubleValue());
    }

    private static String promptVersion(ChatClientRequest request) {
        Object version = request.context().get(PROMPT_VERSION);
        return version == null ? null : version.toString();
    }

    @Override
    public String getName() {
        return "audit";
    }

    @Override
    public int getOrder() {
        // As close to the model as an advisor can sit: the client's own model-calling advisor holds
        // LOWEST_PRECEDENCE and never calls the chain, so ours must rank ahead of it.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
