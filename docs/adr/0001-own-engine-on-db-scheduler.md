# 0001. Own workflow engine on PostgreSQL and db-scheduler instead of Temporal

- Status: accepted
- Date: 2026-10-05 (decided in M1–M2, recorded in M8)
- Deciders: Alex Gorbatov

## Context and problem statement

A quote request lives for days: it waits for the customer's clarification, for an approver, for the customer's
answer to the quote. In between, steps call a language model, the CRM over MCP and a rates API, and any of them
can fail or hang. The process must survive restarts and deployments without losing an instance or running an
external effect twice, and every step must be inspectable afterwards (what went in, what came out, how often
it was tried).

The workflow itself is small: one type (`quote`), twelve states, linear with three waits and a handful of
signals. It runs next to a PostgreSQL database that the application needs anyway.

## Decision drivers

- Durability across crashes and deploys, with timers that fire after hours or days.
- One process plus PostgreSQL to run locally, in CI and in the demo — no extra cluster.
- Plain Spring code in the steps: LLM calls, HTTP and MCP clients, ordinary tests.
- A journal of every attempt that the operator UI can show as a timeline.

## Considered options

1. **Own engine: a state machine persisted in PostgreSQL, scheduled by [db-scheduler](https://github.com/kagkarlsson/db-scheduler).**
2. **Temporal** (Java SDK, self-hosted or Temporal Cloud).
3. **A BPMN engine** (Camunda 7 / Zeebe, Flowable).
4. **Spring Statemachine** with its JPA persistence.

## Decision outcome

Chosen option: **1, own engine on db-scheduler**, because it gives durable steps, timers and retries with the
database the application already has, and the whole engine is a few hundred lines that tests cover end to end.

How it works:

- An instance is one row in `workflow_instance` (state, [context](0002-immutable-context-in-jsonb.md), `version`
  for optimistic locking). Every attempt of a step is a row in `step_execution` with its input, output, status
  and attempt number. Schema: [`V1__workflow.sql`](../../agent-app/src/main/resources/db/migration/V1__workflow.sql),
  [`V2__db_scheduler.sql`](../../agent-app/src/main/resources/db/migration/V2__db_scheduler.sql).
- [`WorkflowEngine`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/WorkflowEngine.java) is the
  only class that changes an instance. A step runs **outside** a transaction; its
  [`StepResult`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/StepResult.java) (`Next`, `Wait`,
  `Fail`) is applied in one short transaction. A crash leaves either the old state or the new one, and a stale
  writer loses on the version check.
- [`WorkflowScheduler`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/WorkflowScheduler.java)
  wraps db-scheduler: an *advance* task per instance (`scheduleIfNotExists`, so a duplicate is harmless), a
  *retry* task with exponential backoff, and *timeout* tasks keyed by instance and state, which a signal
  cancels. db-scheduler's tables live in the same database, so scheduling and the state change commit together.
- Failures: a retryable failure is tried again up to `workflow.engine.max-attempts`, then the instance goes to
  `EXCEPTION`; a [`NonRetryableStepException`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/NonRetryableStepException.java)
  goes there at once. `EXCEPTION` triggers the Investigator and a task for a person.
- Signals from outside (customer replied, approved, rejected, retry) go through
  [`SignalRules`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/SignalRules.java): a signal
  that does not apply to the current state is refused, not queued.
- [`StepRegistry`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/StepRegistry.java) fails
  startup if a working state has no step (`workflow.registry.require-all-states`).

### Consequences

- Good: one dependency (PostgreSQL) for state, timers, outbox and audit. `docker compose up` and Testcontainers
  are enough; the demo runs on a laptop.
- Good: steps are ordinary Spring beans. No determinism rules for workflow code, no activity wrappers around
  model calls.
- Good: the journal (`step_execution`, `tool_call`, `llm_call`) is plain SQL and feeds the timeline UI directly.
- Bad: correctness of the engine is ours to prove. It is covered by
  [`EngineScenariosIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/engine/EngineScenariosIT.java)
  (retries, exhaustion, non-retryable failures, signals, timeouts racing signals),
  [`SchedulerTasksIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/engine/SchedulerTasksIT.java)
  (idempotent scheduling, cancelled and rescheduled timers) and
  [`WorkflowInstanceRepositoryIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/engine/WorkflowInstanceRepositoryIT.java)
  (optimistic locking, unique business key and attempt numbers).
- Bad: steps run **at least once**. A crash after a side effect and before the commit repeats the step, so every
  external effect needs its own idempotency key (see [0004](0004-outbox-and-approval-token.md)).
- Bad: no workflow versioning, no visual designer, no fan-out/fan-in. Fine for one linear workflow; a second,
  branching workflow type would be the moment to reconsider Temporal.

## Pros and cons of the other options

### Temporal

- Good: proven durable execution, versioning, history replay, a UI out of the box.
- Bad: a separate cluster (server, its database, optionally Elasticsearch) to run in dev, CI and the demo.
- Bad: workflow code must be deterministic; every model, MCP and mail call becomes an activity with its own
  retry policy, and the step journal we want for the UI would duplicate Temporal's history.

### A BPMN engine

- Good: visual process models, user tasks built in.
- Bad: heavy for twelve states; the process lives in XML next to the code; user tasks would compete with our
  own approval model (roles from Keycloak, approval tokens).

### Spring Statemachine

- Good: a state machine library in the Spring ecosystem.
- Bad: no durable timers or retries; persistence restores a machine but does not schedule anything. We would
  still need db-scheduler, plus a second abstraction to learn.
