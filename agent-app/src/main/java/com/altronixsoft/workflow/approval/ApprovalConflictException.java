package com.altronixsoft.workflow.approval;

/** The task was already decided, or decided by someone else at the same moment. */
public class ApprovalConflictException extends RuntimeException {

    public ApprovalConflictException(String message) {
        super(message);
    }
}
