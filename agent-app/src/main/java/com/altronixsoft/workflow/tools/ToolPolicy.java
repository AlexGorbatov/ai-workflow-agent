package com.altronixsoft.workflow.tools;

import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Duration;
import java.util.Set;

/**
 * What a tool is allowed to do. {@code allowedIn} lists the workflow states in which a step may use it:
 * the set is fixed by configuration, never by anything an email says.
 */
public record ToolPolicy(ToolKind kind, Set<QuoteState> allowedIn, Duration timeout) {

    public ToolPolicy {
        allowedIn = allowedIn == null ? Set.of() : Set.copyOf(allowedIn);
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
