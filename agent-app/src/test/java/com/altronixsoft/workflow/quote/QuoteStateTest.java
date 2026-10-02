package com.altronixsoft.workflow.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuoteStateTest {

    private static final Set<QuoteState> WAITING =
            EnumSet.of(QuoteState.AWAIT_REPLY, QuoteState.AWAIT_APPROVAL, QuoteState.FOLLOW_UP);
    private static final Set<QuoteState> TERMINAL = EnumSet.of(QuoteState.CLOSED, QuoteState.EXCEPTION);

    @ParameterizedTest
    @EnumSource(QuoteState.class)
    void isWaitingOnlyForAwaitingStates(QuoteState state) {
        assertThat(state.isWaiting()).isEqualTo(WAITING.contains(state));
    }

    @ParameterizedTest
    @EnumSource(QuoteState.class)
    void isTerminalOnlyForClosedAndException(QuoteState state) {
        assertThat(state.isTerminal()).isEqualTo(TERMINAL.contains(state));
    }
}
