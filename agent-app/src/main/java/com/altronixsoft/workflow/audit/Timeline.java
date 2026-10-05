package com.altronixsoft.workflow.audit;

import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An instance's history in time order, with the model's token use and cost. */
public record Timeline(
        UUID instanceId,
        QuoteState state,
        Instant createdAt,
        Instant updatedAt,
        long promptTokens,
        long completionTokens,
        BigDecimal costEur,
        List<TimelineEvent> events) {}
