package com.altronixsoft.workflow.quote;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything a workflow instance knows; persisted as JSON between steps. Immutable: steps return copies. */
public record QuoteContext(
        InboundEmail email,
        List<String> replies,
        QuoteRequest request,
        Customer customer,
        List<CarrierRate> rates,
        Quote quote,
        PolicyDecision policy,
        Investigation investigation,
        String approvalToken,
        String outboundMessageId,
        Set<String> flags,
        String closeReason,
        String error) {

    public QuoteContext {
        replies = replies == null ? List.of() : List.copyOf(replies);
        rates = rates == null ? List.of() : List.copyOf(rates);
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }

    public static QuoteContext of(InboundEmail email) {
        return new QuoteContext(email, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public String fullText() {
        StringBuilder text = new StringBuilder();
        text.append("Subject: ")
                .append(email == null ? null : email.subject())
                .append("\n\n")
                .append(email == null ? null : email.body());
        int n = 1;
        for (String reply : replies) {
            text.append("\n\n--- reply ").append(n++).append(" ---\n").append(reply);
        }
        return text.toString();
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    public QuoteContext withEmail(InboundEmail v) {
        return new QuoteContext(
                v,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withReplies(List<String> v) {
        return new QuoteContext(
                email,
                v,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withRequest(QuoteRequest v) {
        return new QuoteContext(
                email,
                replies,
                v,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withCustomer(Customer v) {
        return new QuoteContext(
                email,
                replies,
                request,
                v,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withRates(List<CarrierRate> v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                v,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withQuote(Quote v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                v,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withPolicy(PolicyDecision v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                v,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withInvestigation(Investigation v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                v,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withApprovalToken(String v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                v,
                outboundMessageId,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withOutboundMessageId(String v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                v,
                flags,
                closeReason,
                error);
    }

    public QuoteContext withCloseReason(String v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                v,
                error);
    }

    public QuoteContext withError(String v) {
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                flags,
                closeReason,
                v);
    }

    public QuoteContext withReply(String reply) {
        List<String> all = new ArrayList<>(replies);
        all.add(reply);
        return withReplies(all);
    }

    /** Merges into the current flags; never replaces them. */
    public QuoteContext withFlags(Set<String> added) {
        Set<String> merged = new LinkedHashSet<>(flags);
        if (added != null) {
            merged.addAll(added);
        }
        return new QuoteContext(
                email,
                replies,
                request,
                customer,
                rates,
                quote,
                policy,
                investigation,
                approvalToken,
                outboundMessageId,
                merged,
                closeReason,
                error);
    }

    public QuoteContext withFlag(String flag) {
        return withFlags(Set.of(flag));
    }
}
