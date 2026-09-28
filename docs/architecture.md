# Architecture

Diagrams for [GUIDE_RU.md §3–4](GUIDE_RU.md#3-архитектура). The guide is authoritative; if a diagram
disagrees with it, fix the diagram.

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

```mermaid
stateDiagram-v2
    [*] --> UNDERSTANDING: new email

    UNDERSTANDING --> ENRICHING: complete request
    UNDERSTANDING --> AWAITING_CUSTOMER_INFO: missing fields / clarification sent
    UNDERSTANDING --> RECORDING: reply ACCEPTS / DECLINES
    UNDERSTANDING --> COMPLETED: OTHER → NOT_A_REQUEST<br/>question or 2 clarifications → HANDED_OFF

    AWAITING_CUSTOMER_INFO --> UNDERSTANDING: customer replied
    AWAITING_CUSTOMER_INFO --> COMPLETED: timeout → NO_RESPONSE

    ENRICHING --> PRICING
    PRICING --> POLICY_CHECK
    PRICING --> COMPLETED: lane not served → HANDED_OFF

    POLICY_CHECK --> RESPONDING: AUTO_APPROVE (policy token)
    POLICY_CHECK --> AWAITING_APPROVAL: REQUIRE_APPROVAL

    AWAITING_APPROVAL --> RESPONDING: approved (user token) / rejected (DECLINE mode)
    AWAITING_APPROVAL --> AWAITING_APPROVAL: SLA overdue

    RESPONDING --> RECORDING: quote email queued
    RESPONDING --> COMPLETED: decline email queued → REJECTED_BY_US

    RECORDING --> AWAITING_CUSTOMER_REPLY: quote written to CRM
    RECORDING --> COMPLETED: status written → QUOTE_ACCEPTED / QUOTE_DECLINED / QUOTE_EXPIRED

    AWAITING_CUSTOMER_REPLY --> UNDERSTANDING: customer replied
    AWAITING_CUSTOMER_REPLY --> AWAITING_CUSTOMER_REPLY: follow-up sent (once)
    AWAITING_CUSTOMER_REPLY --> RECORDING: validUntil passed

    UNDERSTANDING --> NEEDS_ATTENTION: retries exhausted
    ENRICHING --> NEEDS_ATTENTION: retries exhausted
    PRICING --> NEEDS_ATTENTION: retries exhausted
    RESPONDING --> NEEDS_ATTENTION: retries exhausted
    RECORDING --> NEEDS_ATTENTION: outbox FAILED
    NEEDS_ATTENTION --> UNDERSTANDING: operator retry (back to failed state)

    NEEDS_ATTENTION --> CANCELLED: operator cancel
    AWAITING_APPROVAL --> CANCELLED: operator cancel

    COMPLETED --> [*]
    CANCELLED --> [*]
```

`NEEDS_ATTENTION → retry` returns to whichever state failed (`failed_state`); the diagram shows one
edge for readability. Cancel is allowed from every non-terminal state.
