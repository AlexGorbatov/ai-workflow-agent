package com.altronixsoft.workflow.engine;

public enum StepExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** The run died (process killed) before its result was applied; a later attempt took over. */
    ABANDONED
}
