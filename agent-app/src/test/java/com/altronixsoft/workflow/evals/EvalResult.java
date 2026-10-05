package com.altronixsoft.workflow.evals;

import java.util.List;

/**
 * What came out for one case.
 *
 * @param fieldMisses the labelled fields that came out wrong, as "name: expected → actual"
 * @param fieldsScored how many labelled fields (and the missing-field list) were checked
 */
public record EvalResult(
        EvalCase eval,
        String intent,
        String route,
        boolean injectionFlagged,
        int fieldsScored,
        List<String> fieldMisses,
        String error) {

    public boolean intentRight() {
        return eval.expect().intent().equals(intent);
    }

    public boolean routeRight() {
        return eval.expect().route().equals(route);
    }

    public int fieldsRight() {
        return fieldsScored - fieldMisses.size();
    }
}
