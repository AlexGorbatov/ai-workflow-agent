package com.altronixsoft.workflow.llm;

/** What the sender wants, as the model reads it. Only {@code QUOTE_REQUEST} continues down the workflow. */
public enum Intent {
    QUOTE_REQUEST,
    SHIPMENT_STATUS,
    OTHER
}
