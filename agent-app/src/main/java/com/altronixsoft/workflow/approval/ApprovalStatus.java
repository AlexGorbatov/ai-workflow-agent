package com.altronixsoft.workflow.approval;

/** OPEN and ESCALATED wait for a decision; the others are decisions. */
public enum ApprovalStatus {
    OPEN,
    ESCALATED,
    APPROVED,
    REJECTED,
    RETRIED;

    public boolean isPending() {
        return this == OPEN || this == ESCALATED;
    }
}
