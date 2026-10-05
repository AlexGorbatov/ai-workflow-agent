package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Fake steps for engine tests: one per working state, each moving to the next state by default and
 * replaceable per test. Lives in test scope only; with workflow.real-steps=true the real steps replace them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedSteps {

    static final Map<QuoteState, QuoteState> DEFAULT_NEXT = Map.of(
            QuoteState.RECEIVED, QuoteState.UNDERSTOOD,
            QuoteState.UNDERSTOOD, QuoteState.ENRICHED,
            QuoteState.ENRICHED, QuoteState.PRICED,
            QuoteState.PRICED, QuoteState.APPROVED,
            QuoteState.APPROVED, QuoteState.RESPONDED,
            QuoteState.RESPONDED, QuoteState.CLOSED,
            QuoteState.INVESTIGATING, QuoteState.CLOSED);

    static final Map<QuoteState, Function<QuoteContext, StepResult>> behavior = new ConcurrentHashMap<>();
    static final List<QuoteState> calls = new CopyOnWriteArrayList<>();

    public static void reset() {
        behavior.clear();
        calls.clear();
    }

    public static void on(QuoteState state, Function<QuoteContext, StepResult> what) {
        behavior.put(state, what);
    }

    public static List<QuoteState> callsSnapshot() {
        return new ArrayList<>(calls);
    }

    @Bean
    Step received() {
        return step(QuoteState.RECEIVED);
    }

    @Bean
    Step understood() {
        return step(QuoteState.UNDERSTOOD);
    }

    @Bean
    Step enriched() {
        return step(QuoteState.ENRICHED);
    }

    @Bean
    Step priced() {
        return step(QuoteState.PRICED);
    }

    @Bean
    Step approved() {
        return step(QuoteState.APPROVED);
    }

    @Bean
    Step responded() {
        return step(QuoteState.RESPONDED);
    }

    @Bean
    Step investigating() {
        return step(QuoteState.INVESTIGATING);
    }

    private static Step step(QuoteState state) {
        return new Step() {
            @Override
            public QuoteState handles() {
                return state;
            }

            @Override
            public StepResult execute(UUID instanceId, QuoteContext ctx) {
                calls.add(state);
                Function<QuoteContext, StepResult> custom = behavior.get(state);
                return custom != null
                        ? custom.apply(ctx)
                        : new StepResult.Next(DEFAULT_NEXT.get(state), ctx.withFlag("passed-" + state));
            }
        };
    }
}
