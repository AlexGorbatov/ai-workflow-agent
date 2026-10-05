package com.altronixsoft.workflow.approval;

/** The decision is well formed but cannot apply to this task (a price below cost, nothing to approve). */
public class UnprocessableDecisionException extends RuntimeException {

    public UnprocessableDecisionException(String message) {
        super(message);
    }
}
