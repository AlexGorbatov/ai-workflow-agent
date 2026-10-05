package com.altronixsoft.workflow.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One thing that happened to an instance. {@code type} is STEP, LLM_CALL, TOOL_CALL, EMAIL_IN, EMAIL_OUT or
 * APPROVAL; {@code details} carries ids and metadata, never prompt, completion or email text.
 */
public record TimelineEvent(Instant at, String type, String title, String status, Map<String, Object> details) {}
