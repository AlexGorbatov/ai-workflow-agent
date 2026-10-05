package com.altronixsoft.workflow.evals;

import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.engine.StepScope;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.FieldValidator;
import com.altronixsoft.workflow.llm.Guards;
import com.altronixsoft.workflow.llm.LlmCall;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.llm.UnderstandStep;
import com.altronixsoft.workflow.policy.PolicyEngine;
import com.altronixsoft.workflow.quote.CarrierRate;
import com.altronixsoft.workflow.quote.CustomerTier;
import com.altronixsoft.workflow.quote.InboundEmail;
import com.altronixsoft.workflow.quote.PricingCalculator;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteRequest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one case: the real Understand step on the email (one model call), then the route the rest of the flow
 * would take, decided by the real PricingCalculator and PolicyEngine from the case's fixtures, without mail, CRM
 * or rates. What the model extracted is read back from its llm_call row, so it is scored exactly as returned.
 */
public class EvalRunner {

    static final LocalDate PRICED_ON = LocalDate.of(2026, 10, 1);
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-01T08:00:00Z");

    private final UnderstandStep understand;
    private final LlmCallRepository llmCalls;
    private final PricingCalculator pricing;
    private final PolicyEngine policy;
    private final JsonMapper lenient;
    private final FieldValidator validator = new FieldValidator();

    public EvalRunner(
            UnderstandStep understand,
            LlmCallRepository llmCalls,
            PricingCalculator pricing,
            PolicyEngine policy,
            JsonMapper json) {
        this.understand = understand;
        this.llmCalls = llmCalls;
        this.pricing = pricing;
        this.policy = policy;
        this.lenient = json.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public EvalResult run(EvalCase c) {
        UUID id = UUID.randomUUID();
        InboundEmail email = new InboundEmail(
                "<eval-" + c.id() + "-" + id + "@evals.test>",
                null,
                c.email().from(),
                c.email().subject(),
                c.email().body(),
                RECEIVED_AT);
        StepResult result;
        try {
            result = StepScope.call(
                    new StepScope.Current(id, null), () -> understand.execute(id, QuoteContext.of(email)));
        } catch (RuntimeException e) {
            return new EvalResult(c, null, "ERROR", false, scoredFields(c), List.of("the step failed"), e.toString());
        }
        Extraction x = extraction(id);
        QuoteContext ctx = switch (result) {
            case StepResult.Next next -> next.ctx();
            case StepResult.Wait wait -> wait.ctx();
            case StepResult.Fail fail -> null;
        };
        boolean flagged = ctx != null && ctx.hasFlag(Guards.SUSPICIOUS_INSTRUCTIONS);
        List<String> misses = x == null ? List.of("no usable extraction") : misses(c, x);
        return new EvalResult(
                c,
                x == null || x.intent() == null ? null : x.intent().name(),
                route(c, result),
                flagged,
                scoredFields(c),
                misses,
                null);
    }

    /** The route after Understand, as the engine would continue: Enrich from the fixture, then Price and Policy. */
    private String route(EvalCase c, StepResult result) {
        if (result instanceof StepResult.Wait wait && wait.waitState() == QuoteState.AWAIT_REPLY) {
            return "CLARIFY";
        }
        if (!(result instanceof StepResult.Next next)) {
            return "ERROR";
        }
        if (next.next() == QuoteState.CLOSED) {
            return "CLOSED";
        }
        String customer = c.fixtures().customer();
        if ("AMBIGUOUS".equals(customer)) {
            return "INVESTIGATE";
        }
        QuoteContext ctx = next.ctx();
        Set<String> flags = new HashSet<>(ctx.flags());
        CustomerTier tier = switch (customer) {
            case "GOLD" -> CustomerTier.GOLD;
            case "NEW" -> {
                flags.add("NEW_CUSTOMER");
                yield null;
            }
            case "CREDIT_HOLD" -> {
                flags.add("CREDIT_HOLD");
                yield CustomerTier.STANDARD;
            }
            default -> CustomerTier.STANDARD;
        };
        boolean dangerous = ctx.request().isDangerous();
        if (dangerous) {
            flags.add("DANGEROUS_GOODS");
        }
        Quote quote = pricing.price(new CarrierRate("Eval", c.fixtures().cost(), "EUR", 2), tier, dangerous, PRICED_ON);
        return policy.decide(flags, quote).auto() ? "AUTO" : "APPROVAL";
    }

    private Extraction extraction(UUID instanceId) {
        List<LlmCall> calls = llmCalls.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        if (calls.isEmpty() || calls.getLast().getResponse() == null) {
            return null;
        }
        String text = calls.getLast().getResponse().strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            return lenient.readValue(text, Extraction.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int scoredFields(EvalCase c) {
        return c.expect().fields().size() + (isRequest(c) ? 1 : 0);
    }

    private static boolean isRequest(EvalCase c) {
        return "QUOTE_REQUEST".equals(c.expect().intent());
    }

    private List<String> misses(EvalCase c, Extraction x) {
        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, Object> e : c.expect().fields().entrySet()) {
            Object actual = actual(e.getKey(), x);
            if (!matches(e.getKey(), e.getValue(), actual)) {
                misses.add(e.getKey() + ": " + e.getValue() + " → " + actual);
            }
        }
        if (isRequest(c)) {
            Set<String> expected = new LinkedHashSet<>(c.expect().missing());
            Set<String> actual = new LinkedHashSet<>(validator.missing(x));
            if (!expected.equals(actual)) {
                misses.add("missing: " + expected + " → " + actual);
            }
        }
        return misses;
    }

    private static Object actual(String field, Extraction x) {
        return switch (field) {
            case "origin" -> x.origin();
            case "destination" -> x.destination();
            case "weightKg" -> x.weightKg();
            case "pallets" -> x.pallets();
            case "pickupDate" -> x.pickupDate();
            case "language" -> x.language();
            case "dangerous" -> new QuoteRequest(null, null, null, null, x.cargoType(), null, null).isDangerous();
            default -> throw new IllegalArgumentException("Unknown field in a case: " + field);
        };
    }

    static boolean matches(String field, Object expected, Object actual) {
        if (expected instanceof List<?> options) {
            return options.stream().anyMatch(option -> matches(field, option, actual));
        }
        if (actual == null) {
            return false;
        }
        return switch (field) {
            case "weightKg", "pallets" ->
                new BigDecimal(String.valueOf(expected)).compareTo(new BigDecimal(String.valueOf(actual))) == 0;
            case "dangerous" -> Boolean.parseBoolean(String.valueOf(expected)) == (Boolean) actual;
            case "origin", "destination" -> city(String.valueOf(expected)).equals(city(String.valueOf(actual)));
            default ->
                String.valueOf(expected)
                        .strip()
                        .equalsIgnoreCase(String.valueOf(actual).strip());
        };
    }

    /** Lower case, no diacritics, no postcode or country after a comma. */
    static String city(String name) {
        String s = name.strip().toLowerCase(Locale.ROOT).replace('ł', 'l');
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        s = s.replaceAll("\\(.*\\)", "").replaceAll("\\d", "").strip();
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
