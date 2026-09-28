/**
 * {@code ToolGateway}: the single door to external systems — CRM over MCP, rates over REST, email
 * over SMTP — with a per-step tool allowlist, timeouts and {@code tool_call} audit.
 *
 * <p>Every WRITE requires a valid approval token and an idempotency key. Reads arrive in M3, writes in
 * M4 — see docs/milestones/M3.md.
 */
package com.altronixsoft.workflow.tools;
