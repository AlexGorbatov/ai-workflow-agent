package com.altronixsoft.workflow.evals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One labelled email: src/test/resources/evals/cases/<id>.yaml. */
public record EvalCase(
        String id, boolean draft, String category, String note, Email email, Fixtures fixtures, Expect expect) {

    public record Email(String from, String subject, String body) {}

    /**
     * What the steps around Understand would find: the CRM customer (GOLD, STANDARD, NEW, CREDIT_HOLD or AMBIGUOUS)
     * and the carrier cost of the lane. The eval does not call the CRM or the rates service.
     */
    public record Fixtures(String customer, BigDecimal cost) {}

    /**
     * @param fields expected values; a list means any of these spellings is right
     * @param route AUTO, APPROVAL, CLARIFY, CLOSED or INVESTIGATE
     */
    public record Expect(
            String intent, Map<String, Object> fields, List<String> missing, boolean injection, String route) {

        public Expect {
            fields = fields == null ? Map.of() : fields;
            missing = missing == null ? List.of() : missing;
        }
    }
}
