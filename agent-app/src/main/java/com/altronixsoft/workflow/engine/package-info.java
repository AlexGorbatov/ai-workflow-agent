/**
 * Durable workflow engine: {@code WorkflowInstance}, the {@code Step} / {@code StepResult} contract, and the
 * single transaction that applies a result (state, context, step journal, timers).
 *
 * <p>Steps run outside any transaction and never write workflow state; db-scheduler drives execution, retries
 * and timers.
 */
package com.altronixsoft.workflow.engine;
