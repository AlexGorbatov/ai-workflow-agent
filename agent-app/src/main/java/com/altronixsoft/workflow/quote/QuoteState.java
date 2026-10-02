package com.altronixsoft.workflow.quote;

public enum QuoteState {
    RECEIVED,
    AWAIT_REPLY,
    UNDERSTOOD,
    ENRICHED,
    PRICED,
    AWAIT_APPROVAL,
    APPROVED,
    RESPONDED,
    FOLLOW_UP,
    INVESTIGATING,
    CLOSED,
    EXCEPTION;

    public boolean isWaiting() {
        return this == AWAIT_REPLY || this == AWAIT_APPROVAL || this == FOLLOW_UP;
    }

    public boolean isTerminal() {
        return this == CLOSED || this == EXCEPTION;
    }
}
