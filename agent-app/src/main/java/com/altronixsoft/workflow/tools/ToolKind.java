package com.altronixsoft.workflow.tools;

/** READ tools look something up; WRITE tools change the outside world and need an approval token. */
public enum ToolKind {
    READ,
    WRITE
}
