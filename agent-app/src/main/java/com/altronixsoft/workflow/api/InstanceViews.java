package com.altronixsoft.workflow.api;

import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What the instances API returns. The email body and the replies stay out; the sender and subject identify it. */
final class InstanceViews {

    private InstanceViews() {}

    record Item(
            UUID id,
            QuoteState state,
            String customer,
            String route,
            BigDecimal price,
            String currency,
            Set<String> flags,
            Instant createdAt,
            Instant updatedAt) {

        static Item of(WorkflowInstance i) {
            QuoteContext ctx = i.getContext();
            Quote quote = ctx.quote();
            QuoteRequest r = ctx.request();
            return new Item(
                    i.getId(),
                    i.getState(),
                    ctx.customer() != null
                            ? ctx.customer().name()
                            : ctx.email() == null ? null : ctx.email().from(),
                    r == null ? null : r.origin() + " → " + r.destination(),
                    quote == null ? null : quote.price(),
                    quote == null ? null : quote.currency(),
                    ctx.flags(),
                    i.getCreatedAt(),
                    i.getUpdatedAt());
        }
    }

    record Page(List<Item> items, int page, int size, long total) {}

    record Detail(
            UUID id,
            QuoteState state,
            Instant createdAt,
            Instant updatedAt,
            String sender,
            String subject,
            int replies,
            QuoteRequest request,
            Customer customer,
            List<CarrierRate> rates,
            Quote quote,
            PolicyDecision policy,
            Investigation investigation,
            Set<String> flags,
            String closeReason,
            String error) {

        static Detail of(WorkflowInstance i) {
            QuoteContext ctx = i.getContext();
            return new Detail(
                    i.getId(),
                    i.getState(),
                    i.getCreatedAt(),
                    i.getUpdatedAt(),
                    ctx.email() == null ? null : ctx.email().from(),
                    ctx.email() == null ? null : ctx.email().subject(),
                    ctx.replies().size(),
                    ctx.request(),
                    ctx.customer(),
                    ctx.rates(),
                    ctx.quote(),
                    ctx.policy(),
                    ctx.investigation(),
                    ctx.flags(),
                    ctx.closeReason(),
                    ctx.error());
        }
    }

    /** A model call with its full text; for operators only. */
    record LlmCallText(
            UUID id, String model, String promptVersion, String request, String response, String error, Instant at) {}
}
