package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;

public sealed interface Signal {

    record CustomerReplied(String text) implements Signal {}

    record Approved(String approvalToken, BigDecimal price) implements Signal {}

    record Rejected(String reason) implements Signal {}

    record Retry(QuoteState from) implements Signal {}
}
