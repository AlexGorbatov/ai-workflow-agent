# Architecture

Diagrams for [GUIDE_RU.md §3–4](GUIDE_RU.md#3-архитектура). The state machine below follows the code
(`QuoteState`, `StepResult`, `Signal`); GUIDE_RU.md §4.1–4.2 still lists the earlier state names, see its
[§4.0](GUIDE_RU.md#40-актуальная-машина-состояний).

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
            price[Price<br/>quote · BigDecimal]
            policy[Policy<br/>YAML]
            respond[Respond<br/>LLM + numeric guard]
            record[Record]
            investigator[Investigator<br/>LLM, advisory]
        end
        approval[approval<br/>requests · HMAC tokens]
        outbox[outbox<br/>leased dispatcher]
        gateway[ToolGateway<br/>allowlist · token check · audit]
        audit[audit<br/>llm_call · tool_call]
        api[REST API<br/>JWT resource server]
    end

    crm[mock-crm-mcp :8091<br/>MCP server]
    rates[mock-rates :8092<br/>REST]
    llm{{Chat model<br/>OpenAI · Azure · LM Studio · stub}}
    ui[frontend :5173<br/>M5]

    customer -- SMTP --> mailpit
    mailpit -- REST poll --> intake
    intake --> engine
    engine --> steps
    steps -- StepResult --> engine
    engine -- effects --> outbox
    engine --> approval
    understand & respond & investigator -- ChatClient --> llm
    enrich & price -- READ --> gateway
    outbox -- WRITE + token + idempotency key --> gateway
    gateway -- MCP --> crm
    gateway -- HTTP --> rates
    gateway -- SMTP --> mailpit
    mailpit -- reply --> customer
    engine & outbox & approval & audit --- pg
    gateway -.-> audit
    understand & respond & investigator -.-> audit
    operator --> ui -- Bearer JWT --> api
    ui -- PKCE login --> kc
    api -- JWKS --> kc
    api --> engine & approval
    agent -. OTLP, M8 .-> lgtm
```

Reading the arrows:

- Only **engine** changes instance state, and it does so in one transaction per step result.
- Steps **read** through `ToolGateway`; every **write** leaves as an outbox row and passes the gateway
  with an approval token and an idempotency key.
- The model is reached only from the three LLM steps and never gets tools.

## Step execution

```mermaid
sequenceDiagram
    autonumber
    participant S as db-scheduler
    participant E as WorkflowEngine
    participant DB as PostgreSQL
    participant St as Step
    participant G as ToolGateway / ChatClient
    participant O as Outbox dispatcher

    S->>E: advance(instanceId)
    E->>DB: read snapshot (short tx)
    E->>St: execute(context) — no transaction open
    St->>G: LLM call / READ tool (audited)
    G-->>St: result
    St-->>E: StepResult(next, patch, effects, timers)
    E->>DB: tx: check version · update state/context · step_execution<br/>· approval tokens · outbox rows · next tasks
    Note over E,DB: crash before commit → nothing written,<br/>db-scheduler re-runs the step (no external write happened)
    O->>DB: claim PENDING (SKIP LOCKED) · IN_FLIGHT + lease · commit
    O->>G: WRITE(payload, approvalToken, idempotencyKey)
    G-->>O: ok / retryable / permanent
    O->>DB: SENT · or retry · or FAILED → signal NEEDS_ATTENTION
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
a `closeReason`) and `EXCEPTION`. A step failure is retried by the engine (`2^attempt` seconds, three
attempts) before the instance lands in `EXCEPTION`.

Signal table (implemented in M1, T1.5):

| State | Signal | Next state | Context change |
|---|---|---|---|
| `AWAIT_REPLY` | `CustomerReplied` | `RECEIVED` | reply appended to `replies` |
| `AWAIT_APPROVAL` | `Approved` | `APPROVED` | `approvalToken` set, quote carries the approver's price |
| `AWAIT_APPROVAL` | `Rejected` | `CLOSED` | `closeReason = REJECTED` |
| `AWAIT_APPROVAL` | `Retry(from)` | `UNDERSTOOD` or `ENRICHED` | `error` and `investigation` cleared |
| `FOLLOW_UP` | `CustomerReplied` | `CLOSED` | `closeReason = CUSTOMER_REPLIED` |
