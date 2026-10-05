package com.altronixsoft.workflow.tools;

import com.altronixsoft.workflow.quote.QuoteState;
import java.util.UUID;

/** Who is calling: the instance, the state its step runs in, and the approval token if it has one. */
public record CallContext(UUID instanceId, QuoteState state, String approvalToken) {

    public static CallContext of(UUID instanceId, QuoteState state) {
        return new CallContext(instanceId, state, null);
    }
}
