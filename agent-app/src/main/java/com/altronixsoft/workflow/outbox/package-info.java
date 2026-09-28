/**
 * Transactional outbox: effects from a step result become rows in the same transaction as the state
 * change; a leased dispatcher executes them through {@code ToolGateway} with their idempotency key.
 *
 * <p>At-least-once delivery, exactly-once effect where the receiver honours the key. Arrives in M4 —
 * see docs/milestones/M4.md.
 */
package com.altronixsoft.workflow.outbox;
