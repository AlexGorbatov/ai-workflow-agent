-- One row per model call, written by AuditAdvisor. No foreign keys on purpose: an audit row must be
-- storable even when the call happens outside a step, and must outlive whatever it points at.
create table llm_call (
    id                uuid primary key,
    instance_id       uuid,
    step_execution_id uuid,
    model             text,
    prompt_version    text,
    prompt_tokens     int,
    completion_tokens int,
    cost_eur          numeric(12, 6),
    latency_ms        bigint      not null,
    request           text        not null,
    response          text,
    error             text,
    created_at        timestamptz not null
);

create index llm_call_instance_idx on llm_call (instance_id);
create index llm_call_step_execution_idx on llm_call (step_execution_id);
