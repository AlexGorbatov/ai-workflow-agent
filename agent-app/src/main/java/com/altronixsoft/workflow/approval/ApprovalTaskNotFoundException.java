package com.altronixsoft.workflow.approval;

import java.util.UUID;

public class ApprovalTaskNotFoundException extends RuntimeException {

    public ApprovalTaskNotFoundException(UUID id) {
        super("No approval task " + id);
    }
}
