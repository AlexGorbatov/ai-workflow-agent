package com.altronixsoft.workflow.llm;

/** A reply as the model drafted it; it goes nowhere before NumericGuard has checked it. */
public record ReplyDraft(String subject, String body) {}
