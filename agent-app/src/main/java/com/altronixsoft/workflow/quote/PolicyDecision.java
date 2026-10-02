package com.altronixsoft.workflow.quote;

import java.util.List;

public record PolicyDecision(boolean auto, List<String> reasons) {

    public PolicyDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
