package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What the approvals API returns. Facts only: the email text stays out, like it stays out of the policy. */
public final class ApprovalViews {

    private ApprovalViews() {}

    public record Item(
            UUID id,
            UUID instanceId,
            ApprovalKind kind,
            int round,
            ApprovalStatus status,
            List<String> reasons,
            Instant dueAt,
            Instant createdAt,
            String customer,
            String route,
            BigDecimal price,
            String currency) {

        static Item of(ApprovalTask t, QuoteContext ctx) {
            Quote quote = ctx == null ? null : ctx.quote();
            return new Item(
                    t.getId(),
                    t.getInstanceId(),
                    t.getKind(),
                    t.getRound(),
                    t.getStatus(),
                    t.getReasons(),
                    t.getDueAt(),
                    t.getCreatedAt(),
                    ApprovalViews.customer(ctx),
                    ApprovalViews.route(ctx),
                    quote == null ? null : quote.price(),
                    quote == null ? null : quote.currency());
        }
    }

    public record Detail(
            UUID id,
            UUID instanceId,
            QuoteState instanceState,
            ApprovalKind kind,
            int round,
            ApprovalStatus status,
            List<String> reasons,
            Instant dueAt,
            Instant createdAt,
            String decidedBy,
            Instant decidedAt,
            String comment,
            BigDecimal approvedPrice,
            String summary,
            String sender,
            Customer customer,
            QuoteRequest request,
            List<CarrierRate> rates,
            Quote quote,
            Set<String> flags,
            String error,
            Investigation investigation) {

        static Detail of(ApprovalTask t, QuoteState state, QuoteContext ctx, String summary) {
            return new Detail(
                    t.getId(),
                    t.getInstanceId(),
                    state,
                    t.getKind(),
                    t.getRound(),
                    t.getStatus(),
                    t.getReasons(),
                    t.getDueAt(),
                    t.getCreatedAt(),
                    t.getDecidedBy(),
                    t.getDecidedAt(),
                    t.getComment(),
                    t.getApprovedPrice(),
                    summary,
                    ctx.email() == null ? null : ctx.email().from(),
                    ctx.customer(),
                    ctx.request(),
                    ctx.rates(),
                    ctx.quote(),
                    ctx.flags(),
                    ctx.error(),
                    ctx.investigation());
        }
    }

    static String customer(QuoteContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.customer() != null) {
            return ctx.customer().name();
        }
        return ctx.email() == null ? null : ctx.email().from();
    }

    static String route(QuoteContext ctx) {
        QuoteRequest r = ctx == null ? null : ctx.request();
        return r == null ? null : r.origin() + " → " + r.destination();
    }
}
