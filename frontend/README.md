# frontend

The operator UI is **served by agent-app** at <http://localhost:8080/ui/> (sources:
[`agent-app/src/main/resources/static/ui`](../agent-app/src/main/resources/static/ui)). It is plain ES modules with no
build step: three screens over the API, so a bundler and a framework would add more than they save.

- **Instances**: customer, route, amount, state, age; filter by state; the approvals waiting for you (approvers).
- **Timeline**: steps with attempts, model and tool calls, mails, approval tasks, token use and cost.
- **Approval**: the model's summary, the request, rates, price breakdown, reasons; approve (with an edited price),
  reject (with a comment) or retry.

Login is Keycloak's authorization code flow with PKCE (client `workflow-web`, realm `workflow`), written by hand in
`auth.js`. Tokens stay in memory: a reload logs in again, silently while the Keycloak session lasts. The UI shows what
the API returns and sends decisions; prices, rules and permissions are checked by the server.

Run: `docker compose up -d keycloak`, start the mocks and agent-app, open <http://localhost:8080/ui/>, log in as `max`
(operator and approver) or `olena` (operator).

This folder stays free for a richer client later; port 5173 is still registered in the Keycloak client for it.
