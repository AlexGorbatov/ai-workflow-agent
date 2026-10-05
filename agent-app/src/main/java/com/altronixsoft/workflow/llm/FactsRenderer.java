package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.Customer;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The structured facts of an instance as plain lines, for the Investigator. Never the email text, subject or
 * replies: whatever a customer wrote stays out of a prompt that comes with tools. Free-text fields the model
 * extracted earlier (cargo type) are cut short for the same reason.
 */
@Component
public class FactsRenderer {

    static final int MAX_FREE_TEXT = 60;

    public String render(QuoteContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add("error: " + orNone(ctx.error()));
        lines.add(
                "sender: " + (ctx.email() == null ? "none" : orNone(ctx.email().from())));
        lines.add("request: " + request(ctx.request()));
        lines.add("customer: " + customer(ctx.customer()));
        lines.add("flags: "
                + (ctx.flags().isEmpty()
                        ? "none"
                        : String.join(", ", ctx.flags().stream().sorted().toList())));
        lines.add("rates: " + rates(ctx.rates()));
        lines.add("quote: " + quote(ctx.quote()));
        return String.join("\n", lines);
    }

    private static String request(QuoteRequest r) {
        if (r == null) {
            return "none";
        }
        return "origin=%s, destination=%s, weightKg=%s, pallets=%s, cargoType=%s, pickupDate=%s"
                .formatted(
                        cut(r.origin()),
                        cut(r.destination()),
                        r.weightKg() == null ? "none" : r.weightKg().toPlainString(),
                        r.pallets() == null ? "none" : r.pallets(),
                        cut(r.cargoType()),
                        r.pickupDate() == null ? "none" : r.pickupDate());
    }

    private static String customer(Customer c) {
        return c == null ? "not identified" : "%s (%s)".formatted(c.id(), c.tier());
    }

    private static String rates(List<CarrierRate> rates) {
        if (rates.isEmpty()) {
            return "none";
        }
        return rates.stream()
                .map(r -> "%s %s %s, %d days"
                        .formatted(r.carrier(), r.cost().toPlainString(), r.currency(), r.transitDays()))
                .collect(Collectors.joining("; "));
    }

    private static String quote(Quote q) {
        if (q == null) {
            return "none";
        }
        return "%s %s %s, margin %s%%".formatted(q.carrier(), q.price().toPlainString(), q.currency(), q.marginPct());
    }

    private static String cut(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String oneLine = value.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_FREE_TEXT ? oneLine : oneLine.substring(0, MAX_FREE_TEXT) + "…";
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
