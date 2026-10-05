# 0002. Immutable workflow context stored as jsonb

- Status: accepted
- Date: 2026-10-05
- Deciders: Alex Gorbatov

## Context and problem statement

Each step needs what earlier steps found out: the email and the customer's replies, the request as understood,
the CRM customer, carrier rates, the computed quote, the policy decision, the approval token, flags. The data
grows step by step and changes shape as features are added. After the fact, an operator must see what each step
received and returned, and a retried step must start from exactly the input of the failed attempt.

## Decision drivers

- A retry or a replay must see the same input as the first attempt.
- One place that changes state ([0001](0001-own-engine-on-db-scheduler.md)): steps must not write to the
  database behind the engine's back.
- Adding a field must not mean a migration and new tables.
- The timeline UI shows the input and output of every step without a bespoke query per field.

## Considered options

1. **One immutable record, `QuoteContext`, serialized as a jsonb column on the instance and snapshotted into each step execution.**
2. Normalized tables per concept (request, customer, rates, quote, decision) that steps update.
3. A mutable JPA entity graph passed to steps.
4. Event sourcing: store events, fold them into the context.

## Decision outcome

Chosen option: **1, an immutable record in jsonb**, because it makes "a step is a function from context to
result" true in the code and in the database.

- [`QuoteContext`](../../agent-app/src/main/java/com/altronixsoft/workflow/quote/QuoteContext.java) is a Java
  record. Collections are copied into unmodifiable ones in the canonical constructor; every change is a
  `withX(...)` method that returns a new instance. A step receives the context and returns a new one inside its
  [`StepResult`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/StepResult.java).
- [`WorkflowInstance`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/WorkflowInstance.java)
  maps it to `workflow_instance.context jsonb` (`@JdbcTypeCode(SqlTypes.JSON)`, Jackson 3).
  [`StepExecution`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/StepExecution.java) stores the
  context a step started with (`input`) and the one it returned (`output`).
- The engine reads the context in one transaction, runs the step outside it, and writes the result in another
  ([`WorkflowEngine`](../../agent-app/src/main/java/com/altronixsoft/workflow/engine/WorkflowEngine.java)), with
  an optimistic `version` check. A retry re-reads the committed context, so it never sees half of a failed
  attempt.
- What the model extracted ([`Extraction`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/Extraction.java))
  never enters the context: code validates it and builds a
  [`QuoteRequest`](../../agent-app/src/main/java/com/altronixsoft/workflow/quote/QuoteRequest.java) from it. Secrets
  are not in it either, except the approval token, which is short-lived and bound to one instance and amount
  ([0004](0004-outbox-and-approval-token.md)).

### Consequences

- Good: steps are pure enough to unit-test with a hand-built context; no repository mocks.
- Good: the timeline shows input and output of every attempt for free (`step_execution.input/output`), and a
  failed attempt can be compared with the one that succeeded.
- Good: new fields are new record components; old rows deserialize with them as `null`. No migration of the context column since it was created.
- Bad: the context is copied into every `step_execution` row, email text included. Fine at this volume; at scale
  the snapshots would need pruning or the email would move to its own table.
- Bad: queries over context fields use jsonb operators instead of columns; the list views filter on `state` and
  timestamps only, which are real columns.
- Bad: renaming or retyping a field needs care, because old JSON stays in the table.
- Covered by [`QuoteContextJsonTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/quote/QuoteContextJsonTest.java)
  (every field survives a JSON round trip, `with…` methods merge instead of replace, null collections become
  empty) and [`WorkflowInstanceRepositoryIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/engine/WorkflowInstanceRepositoryIT.java)
  (the context survives PostgreSQL, step executions keep input and output, stale writers lose).

## Pros and cons of the other options

### Normalized tables

- Good: typed columns, foreign keys, easy reporting.
- Bad: every new feature adds tables and migrations; steps either write to them (two writers of state) or return
  data the engine maps to six tables. Snapshots for the timeline would need history tables.

### Mutable entity graph

- Good: familiar JPA style.
- Bad: a step that fails halfway leaves a dirty graph; lazy loading outside a transaction breaks, since steps run
  outside one by design; nothing stops a step from saving directly.

### Event sourcing

- Good: full history, replays.
- Bad: an event schema, upcasters and projections for a workflow whose history is already in `step_execution`.
  More moving parts than the problem needs.
