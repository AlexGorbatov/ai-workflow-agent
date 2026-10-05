package com.altronixsoft.workflow.policy;

import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether a priced quote may go out on its own. It looks at flags and numbers only; the email text is
 * not an input, so nothing a customer writes can talk the policy into an approval. Reasons read
 * {@code flag:X}, {@code amount>N} and {@code margin<M}, in the order of the rules.
 */
@Component
public class PolicyEngine {

    static final String NO_QUOTE = "quote:missing";

    private final PolicyRules rules;

    public PolicyEngine(PolicyRules rules) {
        this.rules = rules;
    }

    public PolicyDecision decide(QuoteContext ctx) {
        return decide(ctx.flags(), ctx.quote());
    }

    public PolicyDecision decide(Set<String> flags, Quote quote) {
        List<String> reasons = new ArrayList<>();
        for (String flag : rules.blockingFlags()) {
            if (flags.contains(flag)) {
                reasons.add("flag:" + flag);
            }
        }
        if (quote == null || quote.price() == null) {
            reasons.add(NO_QUOTE);
        } else {
            if (quote.price().compareTo(rules.maxAmount()) > 0) {
                reasons.add("amount>" + plain(rules.maxAmount()));
            }
            BigDecimal margin = Quote.marginPct(quote.cost(), quote.price());
            if (margin.compareTo(rules.minMarginPct()) < 0) {
                reasons.add("margin<" + plain(rules.minMarginPct()));
            }
        }
        return new PolicyDecision(reasons.isEmpty(), reasons);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
