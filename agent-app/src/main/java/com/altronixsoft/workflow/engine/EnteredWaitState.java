package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.util.UUID;

/** Published in the transaction that moves an instance into a waiting state. */
public record EnteredWaitState(UUID instanceId, QuoteState state, QuoteContext ctx) {}
