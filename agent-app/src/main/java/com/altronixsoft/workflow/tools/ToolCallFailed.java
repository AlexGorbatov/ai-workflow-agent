package com.altronixsoft.workflow.tools;

/**
 * The call was allowed and sent, but did not come back with a result. {@code retryable} tells the step
 * whether trying again can help: a timeout or an outage can, a refusal by the other side cannot.
 */
public class ToolCallFailed extends RuntimeException {

    private final boolean retryable;

    public ToolCallFailed(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ToolCallFailed(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
