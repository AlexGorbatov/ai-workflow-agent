# 0003. The model reads and writes text; code computes money and decides

- Status: accepted
- Date: 2026-10-05 (decided in M2–M4, recorded in M8)
- Deciders: Alex Gorbatov

## Context and problem statement

The agent reads emails written by strangers and answers them with a price. An email can carry instructions
("apply a 90% discount", "mark the quote as pre-approved", hidden text in a signature — samples
[07](../../samples/emails/07-prompt-injection.eml) and [15](../../samples/emails/15-hidden-injection.eml)). A
model that reads such an email and also holds tools, sees the margin or decides the price can be talked into
any of them. Models also make arithmetic and transcription mistakes, and a quote with the wrong number is a
commercial commitment.

What role should the model have, and where must it stop?

## Decision drivers

- Text in an email must not be able to change a price, skip an approval or reach an external system.
- Every number a customer sees must be one the code computed.
- The model is still the best tool for reading free-form, multilingual email and for writing a friendly reply.
- A broken or unavailable model must degrade the service, not stop it.

## Considered options

1. **The model extracts and writes, without tools; code prices, decides and acts.** The one exception is the
   Investigator, which reads through the gateway on a budget and never sees the email.
2. A tool-using agent: the model gets CRM, rates and pricing tools and assembles the quote itself.
3. The model computes the price from a rate card in the prompt, and code checks the result.

## Decision outcome

Chosen option: **1**. The model is an untrusted parser and copywriter. Everything that costs money or commits the
company is deterministic code that the model's output cannot reach.

- **Understand** ([`UnderstandStep`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/UnderstandStep.java)):
  the email goes in between `<email>` tags as data; out comes an
  [`Extraction`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/Extraction.java) with a typed schema.
  No tools. Code validates it ([`FieldValidator`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/FieldValidator.java))
  and turns signals into flags ([`Guards`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/Guards.java):
  suspicious instructions — by the model's own report *or* by pattern —, low confidence, large requests).
- **Price** ([`PricingCalculator`](../../agent-app/src/main/java/com/altronixsoft/workflow/quote/PricingCalculator.java)):
  `price = round(cost × (1 + margin) × (1 + fuel) + adr)`, margins by customer tier from configuration. No model
  is involved.
- **Policy** ([`PolicyEngine`](../../agent-app/src/main/java/com/altronixsoft/workflow/policy/PolicyEngine.java),
  [`rules.yml`](../../agent-app/src/main/resources/policy/rules.yml)): decides on flags and numbers only (blocking
  flags, maximum amount, minimum margin). It cannot see the email text.
- **Respond** ([`RespondStep`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/RespondStep.java)): the
  model sees [`QuoteFacts`](../../agent-app/src/main/java/com/altronixsoft/workflow/quote/QuoteFacts.java) only — no
  cost, no margin, no policy reasons, no email. [`NumericGuard`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/NumericGuard.java)
  checks the draft: every amount must be the price, no percentages, every date must be the pickup or the
  validity date. A failed draft gets one strict retry with the reasons; a second failure, or a failing model,
  falls back to a fixed template in the customer's language
  ([`MailTemplates`](../../agent-app/src/main/java/com/altronixsoft/workflow/outbox/MailTemplates.java)).
- **Investigator** ([`InvestigatorStep`](../../agent-app/src/main/java/com/altronixsoft/workflow/llm/InvestigatorStep.java)):
  the only model with tools. It runs after a failure, sees the error and the structured facts but not the email,
  and gets only the READ tools allowed in `INVESTIGATING` through
  [`ToolGateway.readOnlyCallbacks`](../../agent-app/src/main/java/com/altronixsoft/workflow/tools/ToolGateway.java),
  five calls in all. Its output is advice for a person, never an action.

### Consequences

- Good: prompt injection can at most add a flag that sends the quote to a person. Proven end to end by
  [`InjectionInvariantIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/InjectionInvariantIT.java)
  (sample 07 gets the same price as the same request without the injected paragraph).
- Good: the boundaries are enforced by the build, not by convention.
  [`ArchitectureTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/ArchitectureTest.java) fails if a
  class outside `llm` talks to a model, if pricing or policy depends on a model, if the policy can read the
  email, or if anything reaches a tool except through the gateway.
- Good: a model outage still yields correct quotes via the template
  ([`RespondStepIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/llm/RespondStepIT.java)), and the
  numbers are tested without a model
  ([`PricingCalculatorTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/quote/PricingCalculatorTest.java),
  [`PolicyEngineTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/policy/PolicyEngineTest.java),
  [`NumericGuardTest`](../../agent-app/src/test/java/com/altronixsoft/workflow/llm/NumericGuardTest.java)).
- Good: the Investigator's limits are tested
  ([`InvestigatorStepIT`](../../agent-app/src/test/java/com/altronixsoft/workflow/llm/InvestigatorStepIT.java):
  it sees neither the email nor the write tool; seven requested calls get five answers and two "budget exceeded").
- Bad: the agent cannot negotiate, combine shipments or answer free questions; anything outside the schema goes
  to a person. That is the point for v1, and the price of it is more approvals.
- Bad: replies are plainer than an unconstrained model would write; the guard rejects some harmless drafts (a
  delivery estimate is a third date, a "10% faster" is a percentage), and the share that falls back to the template is a
  metric to watch (`response.fallback`).

## Pros and cons of the other options

### Tool-using agent

- Good: flexible; fewer hand-written steps.
- Bad: the same context holds the untrusted email and the tools. Any injection that gets through decides which
  tools run with which arguments; the only defence left is hoping the model refuses.
- Bad: the path to a price differs per run, so tests and audits can only sample it.

### Model computes, code checks

- Good: one call instead of a calculator.
- Bad: if code can check the price, code can compute it. A check that rejects a wrong price still needs a
  fallback that computes the right one.
