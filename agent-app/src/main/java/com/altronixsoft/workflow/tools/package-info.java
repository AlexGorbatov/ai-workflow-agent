/**
 * {@code ToolGateway}: the single door to the CRM (MCP) and the carrier rates (REST), with a policy per tool and
 * state, approval tokens and idempotency keys for writes, schema validation, timeouts and the {@code tool_call}
 * audit. Also the SMTP and Mailpit adapters the outbox and intake use.
 *
 * <p>Every WRITE requires a valid approval token and an idempotency key.
 */
package com.altronixsoft.workflow.tools;
