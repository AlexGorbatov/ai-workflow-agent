package com.altronixsoft.workflow.approval;

/** The actions an approval token can permit. The name is the CRM tool the token is checked against. */
public final class ApprovalActions {

    /** Recording the approved quote in the CRM; the token's {@code maxAmount} is the approved price. */
    public static final String CREATE_OPPORTUNITY = "createOpportunity";

    private ApprovalActions() {}
}
