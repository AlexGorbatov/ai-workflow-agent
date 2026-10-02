package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.util.UUID;

public interface Step {

    QuoteState handles();

    StepResult execute(UUID instanceId, QuoteContext ctx);
}
