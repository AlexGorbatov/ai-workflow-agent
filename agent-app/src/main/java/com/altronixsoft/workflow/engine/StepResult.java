package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Duration;

public sealed interface StepResult {

    record Next(QuoteState next, QuoteContext ctx) implements StepResult {}

    record Wait(QuoteState waitState, Duration timeout, QuoteContext ctx) implements StepResult {}

    record Fail(String reason, boolean retryable) implements StepResult {}
}
