package com.altronixsoft.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepScopeTest {

    @Test
    void nothingIsBoundOutsideAStep() {
        assertThat(StepScope.current()).isEmpty();
    }

    @Test
    void theStepIsVisibleInsideTheCallAndGoneAfterwards() {
        UUID instance = UUID.randomUUID();
        UUID execution = UUID.randomUUID();

        String seen = StepScope.call(new StepScope.Current(instance, execution), () -> {
            assertThat(StepScope.current()).contains(new StepScope.Current(instance, execution));
            return "done";
        });

        assertThat(seen).isEqualTo("done");
        assertThat(StepScope.current()).isEmpty();
    }

    @Test
    void theScopeEndsWhenTheStepThrows() {
        assertThatThrownBy(() -> StepScope.call(new StepScope.Current(UUID.randomUUID(), UUID.randomUUID()), () -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StepScope.current()).isEmpty();
    }
}
