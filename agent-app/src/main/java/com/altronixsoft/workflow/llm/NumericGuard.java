package com.altronixsoft.workflow.llm;

import com.altronixsoft.workflow.quote.QuoteFacts;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks a drafted reply against the facts before it can go to a customer. Every amount next to a euro sign or
 * word must be the quoted price; the price must be there; no percentage may appear (a discount the policy never
 * gave); every date must be the pickup date or the validity date. Returns the violations, empty when the draft is
 * fine. The model's words are free; its numbers are not.
 */
@Component
public class NumericGuard {

    /** Unicode word boundaries: since JDK 19 a plain \b sees only ASCII letters, and "євро" would never match. */
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final String NUMBER =
            "(\\d{1,3}(?:[ \\u00A0\\u202F.,']\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)";
    private static final String CURRENCY = "(?:€|eur\\b|euro\\b|euros\\b|евро\\b|євро\\b)";
    private static final Pattern AMOUNT_AFTER = Pattern.compile(NUMBER + "\\s*" + CURRENCY, FLAGS);
    private static final Pattern AMOUNT_BEFORE = Pattern.compile(CURRENCY + "\\s*" + NUMBER, FLAGS);
    private static final Pattern PERCENT = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*%");

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\b");

    /** Month names and stems in en, de, pl, ru, uk (nominative and genitive), lower case. */
    private static final Map<String, Integer> MONTHS = months();

    private static final Pattern DAY_MONTH_YEAR;
    private static final Pattern MONTH_DAY_YEAR;

    static {
        String names = String.join("|", new TreeSet<>(MONTHS.keySet()).descendingSet());
        DAY_MONTH_YEAR = Pattern.compile("\\b(\\d{1,2})\\.?\\s+(" + names + ")\\.?\\s+(\\d{4})\\b", FLAGS);
        MONTH_DAY_YEAR = Pattern.compile("\\b(" + names + ")\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b", FLAGS);
    }

    public List<String> check(String body, QuoteFacts facts) {
        List<String> violations = new ArrayList<>();
        if (body == null || body.isBlank()) {
            violations.add("the reply is empty");
            return violations;
        }
        checkAmounts(body, facts, violations);
        Matcher percent = PERCENT.matcher(body);
        while (percent.find()) {
            violations.add("a percentage that is not part of the quote: "
                    + percent.group().strip());
        }
        checkDates(body, facts, violations);
        return violations;
    }

    private static void checkAmounts(String body, QuoteFacts facts, List<String> violations) {
        boolean priceStated = false;
        for (Pattern pattern : List.of(AMOUNT_AFTER, AMOUNT_BEFORE)) {
            Matcher m = pattern.matcher(body);
            while (m.find()) {
                BigDecimal amount = normalize(m.group(1));
                if (amount == null || facts.price() == null || amount.compareTo(facts.price()) != 0) {
                    violations.add("an amount that is not the quoted price: "
                            + m.group().strip());
                } else {
                    priceStated = true;
                }
            }
        }
        if (!priceStated) {
            violations.add("the quoted price is not stated");
        }
    }

    /**
     * {@code 1 210,00}, {@code 1.210,00}, {@code 1,210.00}, {@code 1210} → 1210.00. A separator followed by
     * exactly one or two digits at the end is the decimal one; the others group thousands.
     */
    static BigDecimal normalize(String raw) {
        String s = raw.replaceAll("[ \\u00A0\\u202F']", "");
        Matcher decimals = Pattern.compile("[.,](\\d{1,2})$").matcher(s);
        String whole = s;
        String fraction = "";
        if (decimals.find()) {
            whole = s.substring(0, decimals.start());
            fraction = decimals.group(1);
        }
        whole = whole.replaceAll("[.,]", "");
        try {
            return new BigDecimal(fraction.isEmpty() ? whole : whole + "." + fraction);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void checkDates(String body, QuoteFacts facts, List<String> violations) {
        Set<LocalDate> allowed = new HashSet<>();
        if (facts.pickupDate() != null) {
            allowed.add(facts.pickupDate());
        }
        if (facts.validUntil() != null) {
            allowed.add(facts.validUntil());
        }
        String lower = body.toLowerCase(Locale.ROOT);
        dates(ISO_DATE, lower, 1, 2, 3, false, allowed, violations);
        dates(NUMERIC_DATE, lower, 3, 2, 1, false, allowed, violations);
        dates(DAY_MONTH_YEAR, lower, 3, 2, 1, true, allowed, violations);
        dates(MONTH_DAY_YEAR, lower, 3, 1, 2, true, allowed, violations);
    }

    private static void dates(
            Pattern pattern,
            String text,
            int year,
            int month,
            int day,
            boolean monthName,
            Set<LocalDate> allowed,
            List<String> violations) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            LocalDate date;
            try {
                int mm = monthName ? MONTHS.get(m.group(month)) : Integer.parseInt(m.group(month));
                date = LocalDate.of(Integer.parseInt(m.group(year)), mm, Integer.parseInt(m.group(day)));
            } catch (DateTimeException | NullPointerException e) {
                violations.add("an invalid date: " + m.group());
                continue;
            }
            if (!allowed.contains(date)) {
                violations.add("a date that is neither the pickup nor the validity date: " + m.group());
            }
        }
    }

    private static Map<String, Integer> months() {
        String[][] byMonth = {
            {"january", "januar", "stycznia", "styczeń", "января", "январь", "січня", "січень"},
            {"february", "februar", "lutego", "luty", "февраля", "февраль", "лютого", "лютий"},
            {"march", "märz", "marca", "marzec", "марта", "март", "березня", "березень"},
            {"april", "kwietnia", "kwiecień", "апреля", "апрель", "квітня", "квітень"},
            {"may", "mai", "maja", "maj", "мая", "май", "травня", "травень"},
            {"june", "juni", "czerwca", "czerwiec", "июня", "июнь", "червня", "червень"},
            {"july", "juli", "lipca", "lipiec", "июля", "июль", "липня", "липень"},
            {"august", "sierpnia", "sierpień", "августа", "август", "серпня", "серпень"},
            {"september", "września", "wrzesień", "сентября", "сентябрь", "вересня", "вересень"},
            {"october", "oktober", "października", "październik", "октября", "октябрь", "жовтня", "жовтень"},
            {"november", "listopada", "listopad", "ноября", "ноябрь", "листопада", "листопад"},
            {"december", "dezember", "grudnia", "grudzień", "декабря", "декабрь", "грудня", "грудень"}
        };
        Map<String, Integer> months = new HashMap<>();
        for (int i = 0; i < byMonth.length; i++) {
            for (String name : byMonth[i]) {
                months.put(name, i + 1);
            }
        }
        return Map.copyOf(months);
    }
}
