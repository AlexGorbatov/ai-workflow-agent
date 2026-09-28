/**
 * Append-only audit of every LLM call ({@code llm_call}) and tool call ({@code tool_call}),
 * including failures, and the instance timeline built from them.
 *
 * <p>Stores prompts and email text in the database, never in logs. {@code llm_call} arrives in M2,
 * {@code tool_call} in M3 — see docs/milestones/M2.md.
 */
package com.altronixsoft.workflow.audit;
