/**
 * Durable workflow engine: {@code WorkflowInstance}, the {@code Step} / {@code StepResult} contract,
 * and the single transaction that applies a result (state, context, outbox rows, tokens, timers).
 *
 * <p>Steps run outside any transaction and never write state or call external systems; db-scheduler
 * drives execution and recovers dead runs. Arrives in M1 — see docs/milestones/M1.md.
 */
package com.altronixsoft.workflow.engine;
