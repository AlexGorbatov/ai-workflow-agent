package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;

/**
 * The allowed (state, signal) pairs, where each leads, and what changes in the context. Everything
 * else is an {@link InvalidSignalException}.
 */
final class SignalRules {

    record Outcome(QuoteState next, QuoteContext ctx) {}

    private SignalRules() {}

    static Outcome apply(QuoteState state, Signal signal, QuoteContext ctx) {
        return switch (state) {
            case AWAIT_REPLY ->
                switch (signal) {
                    case Signal.CustomerReplied reply -> new Outcome(QuoteState.RECEIVED, ctx.withReply(reply.text()));
                    default -> throw new InvalidSignalException(state, signal);
                };
            case AWAIT_APPROVAL ->
                switch (signal) {
                    case Signal.Approved approved -> new Outcome(QuoteState.APPROVED, approve(ctx, approved));
                    case Signal.Rejected ignored -> new Outcome(QuoteState.CLOSED, ctx.withCloseReason("REJECTED"));
                    case Signal.Retry retry ->
                        new Outcome(retryTarget(retry), ctx.withError(null).withInvestigation(null));
                    default -> throw new InvalidSignalException(state, signal);
                };
            case FOLLOW_UP ->
                switch (signal) {
                    case Signal.CustomerReplied reply ->
                        new Outcome(
                                QuoteState.CLOSED, ctx.withReply(reply.text()).withCloseReason("CUSTOMER_REPLIED"));
                    default -> throw new InvalidSignalException(state, signal);
                };
            default -> throw new InvalidSignalException(state, signal);
        };
    }

    private static QuoteContext approve(QuoteContext ctx, Signal.Approved approved) {
        QuoteContext withToken = ctx.withApprovalToken(approved.approvalToken());
        Quote quote = ctx.quote();
        if (quote == null || approved.price() == null) {
            return withToken;
        }
        return withToken.withQuote(quote.withPrice(approved.price()));
    }

    private static QuoteState retryTarget(Signal.Retry retry) {
        QuoteState from = retry.from();
        if (from != QuoteState.UNDERSTOOD && from != QuoteState.ENRICHED) {
            throw new InvalidSignalException("Retry can restart only from UNDERSTOOD or ENRICHED, not " + from);
        }
        return from;
    }
}
