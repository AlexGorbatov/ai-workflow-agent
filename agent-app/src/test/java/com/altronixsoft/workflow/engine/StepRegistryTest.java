package com.altronixsoft.workflow.engine;

import static com.altronixsoft.workflow.engine.TestData.stepFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.quote.QuoteState;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepRegistryTest {

    private static List<Step> stepsForAllWorkingStates() {
        return Arrays.stream(QuoteState.values())
                .filter(s -> !s.isWaiting() && !s.isTerminal())
                .map(TestData::stepFor)
                .toList();
    }

    @Test
    void returnsTheStepRegisteredForAState() {
        Step understand = stepFor(QuoteState.RECEIVED);
        StepRegistry registry = new StepRegistry(List.of(understand), false);

        assertThat(registry.forState(QuoteState.RECEIVED)).isSameAs(understand);
    }

    @Test
    void failsOnStartupWhenTwoStepsClaimTheSameState() {
        assertThatThrownBy(() ->
                        new StepRegistry(List.of(stepFor(QuoteState.RECEIVED), stepFor(QuoteState.RECEIVED)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECEIVED");
    }

    @Test
    void refusesAStepForAWaitingOrTerminalState() {
        assertThatThrownBy(() -> new StepRegistry(List.of(stepFor(QuoteState.AWAIT_APPROVAL)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWAIT_APPROVAL");
        assertThatThrownBy(() -> new StepRegistry(List.of(stepFor(QuoteState.CLOSED)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void forStateFailsWhenNoStepIsRegistered() {
        StepRegistry registry = new StepRegistry(List.of(), false);

        assertThatThrownBy(() -> registry.forState(QuoteState.PRICED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRICED");
    }

    @Test
    void whenAllStatesAreRequiredAMissingStepFailsStartupAndNamesIt() {
        List<Step> steps = stepsForAllWorkingStates().stream()
                .filter(s -> s.handles() != QuoteState.ENRICHED)
                .toList();

        assertThatThrownBy(() -> new StepRegistry(steps, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENRICHED");
    }

    @Test
    void whenAllStatesAreRequiredACompleteSetIsAccepted() {
        StepRegistry registry = new StepRegistry(stepsForAllWorkingStates(), true);

        assertThat(registry.forState(QuoteState.INVESTIGATING).handles()).isEqualTo(QuoteState.INVESTIGATING);
    }
}
