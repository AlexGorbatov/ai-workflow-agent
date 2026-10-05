package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import java.time.Duration;
import java.util.UUID;

/**
 * What happens when a customer has not answered a quote: the first time, a reminder is queued and the instance
 * waits again; the second time, it closes. The engine calls this in the transaction that records the timeout.
 */
public interface FollowUpHandler {

    /** Set on the instance once the reminder is queued. */
    String FOLLOW_UP_SENT = "FOLLOW_UP_SENT";

    /** Queues the reminder (idempotently) and returns how long to wait for an answer to it. */
    Duration remind(UUID instanceId, QuoteContext ctx);
}
