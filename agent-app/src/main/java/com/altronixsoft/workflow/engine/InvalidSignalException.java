package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;

/** The signal does not apply to the instance's current state; nothing was changed. */
public class InvalidSignalException extends RuntimeException {

    public InvalidSignalException(QuoteState state, Signal signal) {
        super("Signal " + signal.getClass().getSimpleName() + " is not allowed in state " + state);
    }

    public InvalidSignalException(String message) {
        super(message);
    }
}
