package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import java.util.UUID;

/**
 * What the scheduler calls when a task fires. {@code WorkflowEngine} implements it; the indirection
 * keeps the scheduler tasks and the engine from depending on each other at bean-creation time.
 */
public interface InstanceRunner {

    void advance(UUID instanceId);

    /** {@code expected} is the state the timer was set for; the engine ignores it if the instance moved on. */
    void onTimeout(UUID instanceId, QuoteState expected);
}
