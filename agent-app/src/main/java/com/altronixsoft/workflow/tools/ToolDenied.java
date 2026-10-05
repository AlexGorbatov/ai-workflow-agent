package com.altronixsoft.workflow.tools;

/** The gateway refused the call; nothing was sent. Retrying the same call cannot succeed. */
public class ToolDenied extends RuntimeException {

    public ToolDenied(String message) {
        super(message);
    }
}
