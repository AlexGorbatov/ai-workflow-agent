/**
 * The instance timeline (steps, model and tool calls, mails, approvals) built from the audit tables
 * {@code step_execution}, {@code llm_call} and {@code tool_call}, and the business metrics.
 *
 * <p>Prompts and email text stay in the database, never in logs.
 */
package com.altronixsoft.workflow.audit;
