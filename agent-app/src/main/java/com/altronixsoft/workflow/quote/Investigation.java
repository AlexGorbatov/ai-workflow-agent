package com.altronixsoft.workflow.quote;

import java.util.List;

public record Investigation(String summary, String likelyCause, String suggestedAction, List<String> evidence) {

    public Investigation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
