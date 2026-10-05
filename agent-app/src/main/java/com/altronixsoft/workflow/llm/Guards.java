package com.altronixsoft.workflow.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Signals about an email for the policy to weigh. They are not a defense: what protects the price is that the
 * email text never reaches the calculation or the rules (M4). A flag only sends a doubtful case to a person.
 */
@Component
public class Guards {

    public static final String SUSPICIOUS_INSTRUCTIONS = "SUSPICIOUS_INSTRUCTIONS";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String LARGE_REQUEST = "LARGE_REQUEST";

    private static final List<Pattern> INJECTION = List.of(
            pattern("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|rules|prompts?)"),
            pattern("(disregard|forget)\\s+(all\\s+)?(the\\s+)?(previous|prior|above|your)\\s+(instructions|rules)"),
            pattern("(note|message|instructions?)\\s+to\\s+(the\\s+)?(ai|assistant|model|llm|bot)"),
            pattern("\\bassistant[- ]instructions\\b"),
            pattern("\\bsystem\\s*(note|prompt|message|override)\\b"),
            pattern("\\bpre-?approved\\b"),
            pattern("(apply|give|grant)\\s+(me\\s+|us\\s+)?(a\\s+)?\\d{1,3}\\s?%\\s*discount"),
            pattern("(skip|bypass|disable|without)\\s+(the\\s+)?(human\\s+)?(review|approval|policy)"),
            pattern("\\byou\\s+are\\s+now\\b"),
            pattern("(list|names?)\\s+of\\s+(your\\s+)?other\\s+customers"),
            pattern("ignoriere?\\s+(alle\\s+)?(vorherigen|bisherigen|obigen)\\s+(anweisungen|regeln)"),
            pattern("игнорируй(те)?\\s+(все\\s+)?(предыдущие|прошлые|прежние)\\s+(инструкции|правила)"),
            pattern("ігноруй(те)?\\s+(усі\\s+|всі\\s+)?(попередні|минулі)\\s+(інструкції|правила)"));

    private final GuardProperties properties;

    Guards(GuardProperties properties) {
        this.properties = properties;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public Set<String> inspect(String emailText, Extraction x) {
        Set<String> flags = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(x.containsInstructionsToAssistant()) || looksLikeInjection(emailText)) {
            flags.add(SUSPICIOUS_INSTRUCTIONS);
        }
        if (x.confidence() == null || x.confidence() < properties.lowConfidence()) {
            flags.add(LOW_CONFIDENCE);
        }
        boolean manyPallets = x.pallets() != null && x.pallets() >= properties.largePallets();
        boolean heavy = x.weightKg() != null && x.weightKg().compareTo(properties.largeWeightKg()) >= 0;
        if (manyPallets || heavy) {
            flags.add(LARGE_REQUEST);
        }
        return flags;
    }

    private static boolean looksLikeInjection(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return INJECTION.stream().anyMatch(p -> p.matcher(normalized).find());
    }
}
