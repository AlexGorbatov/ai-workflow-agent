package com.altronixsoft.workflow.approvaltoken;

/** The token does not permit what was asked. {@link #reason()} says why; the message never contains the token. */
public class InvalidApprovalTokenException extends RuntimeException {

    public enum Reason {
        MALFORMED,
        BAD_SIGNATURE,
        EXPIRED,
        WRONG_ACTION,
        AMOUNT_EXCEEDED
    }

    private final Reason reason;

    public InvalidApprovalTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
