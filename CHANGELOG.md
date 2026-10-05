# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] — 2026-10-05

The first complete version: email in, priced and approved reply out, with a CRM record and a follow-up.

### Added

- **Demo.** `scripts/demo.sh` runs the whole system from a fresh clone with no API key: compose
  infrastructure, the mock CRM and rates, agent-app with the `demo` profile (timers in seconds and minutes) and a
  canned demo model that knows the fifteen sample emails; then it sends the samples and prints where to look.
  `LM_STUDIO=1` uses a local model instead. Four ADRs in `docs/adr`, README rewritten.
- **Observability and evals.** Spans for every step, tool and model call (`workflow.step`, `workflow.tool`,
  Spring AI), business metrics (`workflow.instances`, `approvals.open`, `outbox.failed`, `response.fallback`,
  `llm.tokens`, `llm.cost.eur`), model cost per call, OTLP export to Grafana LGTM with a provisioned dashboard.
  40 labelled eval cases with thresholds for intent, fields, route and injection recall; a harness check in
  every build and a weekly real-model workflow.
- **Respond, record, follow-up.** Replies drafted from quote facts only and checked by `NumericGuard` (every
  amount is the price, no percentages, only the pickup and validity dates), with a strict retry and a template
  fallback in five languages. The CRM opportunity is written with the approval token and an idempotency key. A
  quote gets one reminder, then closes; an outbox relay with exponential backoff and a failure metric.
- **Approvals and operator UI.** Approval tasks with the policy's reasons and a model-written briefing, roles
  from Keycloak (`operator`, `approver`), approve with an edited price, reject with a comment, retry from a step,
  SLA escalation by mail. Instance list and timeline API; a static operator UI with OIDC and PKCE.
- **Pricing, policy, Investigator.** Deterministic pricing from carrier rates (tier margins, fuel surcharge,
  ADR surcharge, rounding), YAML policy on flags and numbers, approval tokens issued by the policy for automatic
  quotes. An Investigator explains requests that cannot be priced (ambiguous customer, no rates) using read-only
  tools on a budget of five calls.
- **Tools.** `ToolGateway` with five checks on every call (policy per state, token and idempotency key for
  writes, JSON-schema validation, timeout, audit), an MCP client for the mock CRM (Streamable HTTP), a REST client
  for mock rates, the Enrich step, and the `approval-token` module (HMAC-SHA256) shared with the CRM.
- **Intake and Understand.** Mail intake from Mailpit with deduplication by Message-ID and threading of
  replies; structured extraction with guards (suspicious instructions, low confidence, large requests);
  clarification mails in the customer's language and resume on reply.
- **Engine.** Durable state machine on PostgreSQL and db-scheduler: step journal, retries with backoff,
  non-retryable failures, signals, timers keyed by instance and state, optimistic locking.
- **Skeleton.** Maven multi-module build (agent-app, mock-crm-mcp, mock-rates, approval-token), profiles for
  OpenAI, Azure OpenAI and LM Studio, OAuth2 resource server, Testcontainers, Docker Compose, CI, ArchUnit rules
  for the invariants.

### Changed

- `response.fallback` and the rejected-draft counter are registered at start, so the dashboard shows 0 % rather
  than no data before the first fallback.
- The clarification wait is configurable (`workflow.clarification-timeout`, default 72 h).
- Mock-app jars for integration tests are copied without their version (`mock-crm-mcp-exec.jar`), so a version
  bump does not leave a stale jar in use.

[Unreleased]: https://github.com/AlexGorbatov/ai-workflow-agent/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/AlexGorbatov/ai-workflow-agent/releases/tag/v1.0.0
