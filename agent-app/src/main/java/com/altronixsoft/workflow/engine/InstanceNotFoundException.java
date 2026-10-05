package com.altronixsoft.workflow.engine;

import java.util.UUID;

public class InstanceNotFoundException extends RuntimeException {

    public InstanceNotFoundException(UUID id) {
        super("No workflow instance " + id);
    }
}
