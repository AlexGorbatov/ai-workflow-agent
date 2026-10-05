# Architecture decision records

The decisions that shape the agent, in [MADR](https://adr.github.io/madr/) form. Each links to the classes that
implement it and the tests that hold it in place.

| # | Decision | Status |
|---|---|---|
| [0001](0001-own-engine-on-db-scheduler.md) | Own workflow engine on PostgreSQL and db-scheduler instead of Temporal | accepted |
| [0002](0002-immutable-context-in-jsonb.md) | Immutable workflow context stored as jsonb | accepted |
| [0003](0003-llm-without-tools-code-computes-money.md) | The model reads and writes text; code computes money and decides | accepted |
| [0004](0004-outbox-and-approval-token.md) | External writes go through an outbox or carry an approval token and an idempotency key | accepted |
