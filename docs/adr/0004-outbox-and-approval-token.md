# 0004. External writes go through an outbox or carry an approval token and an idempotency key

- Status: accepted
- Date: 2026-10-05
- Deciders: Alex Gorbatov

## Context and problem statement

The workflow has two kinds of effect outside its database: mail to customers (clarifications, quotes,
reminders, escalations) and a write to the CRM (an opportunity for a sent quote). Steps run at least once
([0001](0001-own-engine-on-db-scheduler.md)): a crash between the effect and the commit repeats the step. A
mail sent twice confuses a customer; an opportunity created twice inflates the pipeline; an opportunity with an
amount nobody approved is worse than either. The CRM is a separate system that must be able to refuse a write
on its own, without trusting the agent's word.

## Decision drivers

- No effect without a decision: a write must prove that a policy or a person approved exactly this action and
  amount.
- Repeating a step must not repeat its effect.
- A mail server or CRM outage must not fail the workflow step that wanted the effect.
- Every attempt, refused ones included, is audited.

## Considered options

1. **Mail through an outbox with a per-instance dedupe key; the CRM write through `ToolGateway` with an HMAC
   approval token and an idempotency key, verified again by the CRM.**
2. Send mail and call the CRM directly from the steps, with retries.
3. Distributed transaction (XA) or a saga framework across the database, SMTP and the CRM.

## Decision outcome

Chosen option: **1**.

**Mail: outbox.**

- A step does not send; it queues a row with
  [`OutboxService.enqueueEmail`](../../agent-app/src/main/java/com/altronixsoft/workflow/outbox/OutboxService.java).
  The row's key is `instanceId:kind` (`QUOTE`, `CLARIFICATION-1`, `FOLLOW-UP-1`, `ESCALATION-…`) and the insert is
  `on conflict (dedupe_key) do nothing`, so a repeated step queues nothing new. The Message-ID derives from the
  same key. Schema: [`V4__email_thread_outbox.sql`](../../agent-app/src/main/resources/db/migration/V4__email_thread_outbox.sql).
- [`OutboxRelay`](../../agent-app/src/main/java/com/altronixsoft/workflow/outbox/OutboxRelay.java) claims due
  rows with `for update skip locked`, sends them, marks them sent and links the Message-ID to the instance (so the
  customer's answer finds it) in one transaction. A crash after the SMTP hand-over and before the commit sends
  the mail again **with the same Message-ID**: at-least-once, and the receiver can recognize the duplicate.
- A failed send waits twice as long each time; after `workflow.outbox.max-attempts` the row is `FAILED` and
  counted in `outbox.failed`. The workflow has moved on regardless.

**CRM write: approval token + idempotency key.**

- An approval is a token: [`ApprovalTokens`](../../approval-token/src/main/java/com/altronixsoft/workflow/approvaltoken/ApprovalTokens.java)
  signs [`ApprovalClaims`](../../approval-token/src/main/java/com/altronixsoft/workflow/approvaltoken/ApprovalClaims.java)
  (instance, action, maximum amount, who approved, expiry) with HMAC-SHA256 and a shared secret of at least 32
  characters. It is a separate module, so the CRM can depend on it without depending on the agent.
- The policy issues one for an automatic approval
  ([`PolicyStep`](../../agent-app/src/main/java/com/altronixsoft/workflow/policy/PolicyStep.java), approved by
  `system:policy`); a person issues one by approving in the UI
  ([`ApprovalService`](../../agent-app/src/main/java/com/altronixsoft/workflow/approval/ApprovalService.java),
  for the price the approver set). Both cover `createOpportunity` up to that price only.
- [`ToolGateway`](../../agent-app/src/main/java/com/altronixsoft/workflow/tools/ToolGateway.java) refuses a WRITE
  without a token or without an `idempotencyKey`, allows `createOpportunity` only in `RESPONDED`, and adds the
  token from the context to the arguments itself, so the audit row never stores it.
  [`RecordStep`](../../agent-app/src/main/java/com/altronixsoft/workflow/crm/RecordStep.java) uses the key
  `instanceId:opportunity`.
- The CRM ([`CrmTools`](../../mock-crm-mcp/src/main/java/com/altronixsoft/workflow/mockcrm/CrmTools.java))
  verifies the token again — signature, action, expiry, amount within the limit — and returns the first
  opportunity for a key it has already seen.

### Consequences

- Good: a step can be retried any number of times; the quote mail and the opportunity exist once.
  [`RespondStepIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/llm/RespondStepIT.java) (running the
  step again queues no second mail),
  [`RecordStepIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/crm/RecordStepIT.java) (one
  opportunity even when the step runs twice),
  [`OutboxRelayIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/outbox/OutboxRelayIT.java) (two relays
  running together send every mail exactly once).
- Good: the CRM does not have to trust the agent. A token for another action, a higher amount, an expired token
  or a forged one is refused
  ([`ApprovalTokensTest`](../../approval-token/src/test/java/com/altronixsoft/workflow/approvaltoken/ApprovalTokensTest.java),
  [`CrmToolsIT`](../../mock-crm-mcp/src/test/java/com/altronixsoft/workflow/mockcrm/CrmToolsIT.java)), and the
  gateway's own checks are covered by
  [`ToolGatewayTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/tools/ToolGatewayTest.java).
- Good: SMTP or CRM outages are retries, not failed instances
  ([`OutboxRelayFailureIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/outbox/OutboxRelayFailureIT.java)).
- Bad: the outbox row is written when the step runs, not in the transaction that moves the state. If the
  state change is then lost, the step runs again and finds its row already there — fine for the same content,
  but a mail can leave for a step whose result was never committed. Acceptable here because the mail is the
  step's whole effect and its content is deterministic for the quote it carries.
- Bad: delivery is at-least-once; a duplicate with the same Message-ID is possible after a crash.
- Bad: a symmetric secret is shared between the agent and the CRM. Rotating it invalidates open tokens; a real
  CRM integration would use asymmetric signatures (the agent signs, the CRM holds only the public key). The mock
  CRM also does not check the token's instance id, because it has no notion of workflow instances.

## Pros and cons of the other options

### Direct calls with retries

- Good: simplest code.
- Bad: retries repeat effects unless every receiver deduplicates; an SMTP outage fails the step that only
  wanted to queue a mail; nothing stops a step from writing without approval.

### XA / saga framework

- Good: stronger guarantees on paper.
- Bad: SMTP and an MCP server do not take part in XA; a saga framework adds compensations for effects that
  cannot be undone (a sent mail). The outbox plus idempotency keys give the same practical result with plain SQL.
