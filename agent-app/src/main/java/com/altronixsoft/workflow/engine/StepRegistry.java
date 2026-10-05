package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Maps each working state to the one step that handles it; fails fast on conflicts. */
public class StepRegistry {

    private final Map<QuoteState, Step> steps = new EnumMap<>(QuoteState.class);

    /**
     * @param requireAllStates when true, every working state (not waiting, not terminal) must have a step
     */
    public StepRegistry(List<Step> candidates, boolean requireAllStates) {
        for (Step step : candidates) {
            QuoteState state = step.handles();
            if (state.isWaiting() || state.isTerminal()) {
                throw new IllegalStateException(
                        "Step " + step.getClass().getName() + " handles " + state + ", which is not a working state");
            }
            Step previous = steps.putIfAbsent(state, step);
            if (previous != null) {
                throw new IllegalStateException("Two steps handle " + state + ": "
                        + previous.getClass().getName() + " and "
                        + step.getClass().getName());
            }
        }
        if (requireAllStates) {
            String missing = Arrays.stream(QuoteState.values())
                    .filter(s -> !s.isWaiting() && !s.isTerminal())
                    .filter(s -> !steps.containsKey(s))
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            if (!missing.isEmpty()) {
                throw new IllegalStateException("No step handles: " + missing);
            }
        }
    }

    public Step forState(QuoteState state) {
        Step step = steps.get(state);
        if (step == null) {
            throw new IllegalStateException("No step handles " + state);
        }
        return step;
    }
}
