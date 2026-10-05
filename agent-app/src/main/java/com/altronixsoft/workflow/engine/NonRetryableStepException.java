package com.altronixsoft.workflow.engine;

/** Thrown by a step when retrying cannot help; the engine turns it into {@code Fail(retryable=false)}. */
public class NonRetryableStepException extends RuntimeException {

    public NonRetryableStepException(String message) {
        super(message);
    }

    public NonRetryableStepException(String message, Throwable cause) {
        super(message, cause);
    }
}
