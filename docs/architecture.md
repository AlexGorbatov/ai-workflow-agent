# Architecture

How the parts fit together, how a step runs, and the state machine. The reasons behind the main choices are in
the [ADRs](adr/README.md).

## Components

```mermaid
flowchart LR
    customer([Customer mailbox])
    operator([Operator / approver<br/>browser])

    subgraph infra[compose.yaml]
        mailpit[(Mailpit<br/>SMTP 1025 · API 8025)]
        pg[(PostgreSQL 17<br/>state · outbox · audit<br/>db-scheduler tasks)]
        kc[Keycloak 8180<br/>realm workflow]
        lgtm[Grafana LGTM<br/>OTLP 4317/4318]
    end

    subgraph agent[agent-app :8080]
        intake[intake<br/>poll · dedupe · threads]
        engine[engine<br/>state machine<br/>StepResult → 1 tx]
        subgraph steps[steps]
            understand[Understand<br/>LLM]
            enrich[Enrich]
            price[Price<br/>code]
            policy[Policy<br/>YAML]
            respond[Respond<br/>LLM + numeric guard]
            record[Record]
            investigator[Investigator<br/>LLM, read-only tools]
        end
        approval[approval<br/>tasks · SLA · HMAC tokens]
        outbox[outbox<br/>relay, SKIP LOCKED]
        gateway[ToolGateway<br/>policy · token · schema · timeout · audit]
        audit[audit<br/>llm_call · tool_call]
        api[REST API + operator UI<br/>JWT resource server]
    end

    crm[mock-crm-mcp :8091<br/>MCP server]
    rates[mock-rates :8092<br/>REST]
    llm{{Chat model<br/>OpenAI · Azure · LM Studio · stub}}

    customer -- SMTP --> mailpit
    mailpit -- REST poll --> intake
    intake --> engine
    engine --> steps
    steps -- StepResult --> engine
    understand & respond -- queue mail --> outbox
    outbox -- SMTP --> mailpit
    engine --> approval
    understand & respond & investigator -- ChatClient --> llm
    enrich & price & investigator -- READ --> gateway
    record -- WRITE + token + idempotency key --> gateway
    gateway -- MCP --> crm
    gateway -- HTTP --> rates
    mailpit -- reply --> customer
    engine & outbox & approval & audit --- pg
    gateway -.-> audit
    understand & respond & investigator -.-> audit
    operator -- PKCE login --> kc
    operator -- Bearer JWT --> api
    api -- JWKS --> kc
    api --> engine & approval
    agent -. OTLP traces and metrics .-> lgtm
```

Reading the arrows:

- Only **engine** changes instance state, in one transaction per step result.
- External systems are reached only through `ToolGateway`; the one **write** (the CRM opportunity) carries an
  approval token and an idempotency key. Mail leaves through the outbox.
- A model is reached only from the LLM steps; only the Investigator gets tools, read-only and on a budget.

## Step execution

```mermaid
sequenceDiagram
    autonumber
    participant S as db-scheduler
    participant E as WorkflowEngine
    participant DB as PostgreSQL
    participant St as Step
    participant G as ToolGateway / ChatClient
    participant R as Outbox relay

    S->>E: advance(instanceId)
    E->>DB: tx: read instance · step_execution RUNNING (attempt n)
    E->>St: execute(context) — no transaction open
    St->>G: model call / tool call (audited)
    G-->>St: result
    St->>DB: queue mail (outbox, key instanceId:kind, insert-if-absent)
    St-->>E: StepResult: Next · Wait · Fail
    E->>DB: tx: check version · new state and context · step_execution SUCCEEDED/FAILED<br/>· timer or retry task
    Note over E,DB: crash before the commit → the state is unchanged and the step runs again;<br/>its mail row and CRM write are keyed, so they happen once
    R->>DB: claim due rows (FOR UPDATE SKIP LOCKED)
    R->>R: send over SMTP with a stable Message-ID
    R->>DB: SENT · or retry later · or FAILED after max attempts
```

## State machine

The state means **which step runs next** (or what the instance is waiting for). It is the `QuoteState`
enum in `agent-app/.../quote/QuoteState.java`; the transitions below are what `WorkflowEngine` applies
from `StepResult` and `Signal` (`engine/`).

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: new email (engine.start)

    RECEIVED --> UNDERSTOOD: Understand — complete request
    RECEIVED --> AWAIT_REPLY: Understand — fields missing, clarification queued
    RECEIVED --> CLOSED: Understand — not a quote request

    AWAIT_REPLY --> RECEIVED: CustomerReplied
    AWAIT_REPLY --> CLOSED: timeout → NO_RESPONSE

    UNDERSTOOD --> ENRICHED: Enrich (CRM read)
    UNDERSTOOD --> INVESTIGATING: ambiguous customer
    ENRICHED --> PRICED: Price (code)
    ENRICHED --> INVESTIGATING: no rates

    PRICED --> APPROVED: Policy — auto (policy token)
    PRICED --> AWAIT_APPROVAL: Policy — approval required

    INVESTIGATING --> AWAIT_APPROVAL: Investigator (LLM, read-only)

    AWAIT_APPROVAL --> APPROVED: Approved (user token, price)
    AWAIT_APPROVAL --> CLOSED: Rejected → REJECTED
    AWAIT_APPROVAL --> UNDERSTOOD: Retry(from)
    AWAIT_APPROVAL --> ENRICHED: Retry(from)

    APPROVED --> RESPONDED: Respond (LLM + numeric guard, reply queued in outbox)
    RESPONDED --> FOLLOW_UP: Record (CRM write)

    FOLLOW_UP --> FOLLOW_UP: timeout → one reminder
    FOLLOW_UP --> CLOSED: timeout → NO_RESPONSE
    FOLLOW_UP --> CLOSED: CustomerReplied → CUSTOMER_REPLIED

    RECEIVED --> EXCEPTION: retries exhausted
    UNDERSTOOD --> EXCEPTION: retries exhausted
    ENRICHED --> EXCEPTION: retries exhausted
    PRICED --> EXCEPTION: retries exhausted
    APPROVED --> EXCEPTION: retries exhausted
    RESPONDED --> EXCEPTION: retries exhausted

    CLOSED --> [*]
    EXCEPTION --> [*]
```

Waiting states are `AWAIT_REPLY`, `AWAIT_APPROVAL` and `FOLLOW_UP`; terminal states are `CLOSED` (with
a `closeReason`) and `EXCEPTION`. A step failure is retried by the engine (exponential backoff,
`workflow.engine.max-attempts`, three by default) before the instance lands in `EXCEPTION`. A request the
workflow cannot price (ambiguous customer, no rates for the lane) goes to `INVESTIGATING` instead: the
Investigator writes notes and a person decides.

Signals:

| State | Signal | Next state | Context change |
|---|---|---|---|
| `AWAIT_REPLY` | `CustomerReplied` | `RECEIVED` | reply appended to `replies` |
| `AWAIT_APPROVAL` | `Approved` | `APPROVED` | `approvalToken` set, quote carries the approver's price |
| `AWAIT_APPROVAL` | `Rejected` | `CLOSED` | `closeReason = REJECTED` |
| `AWAIT_APPROVAL` | `Retry(from)` | `UNDERSTOOD` or `ENRICHED` | `error` and `investigation` cleared |
| `FOLLOW_UP` | `CustomerReplied` | `CLOSED` | `closeReason = CUSTOMER_REPLIED` |
