-- One row per call that went through ToolGateway, including the ones it refused. Like llm_call, no foreign
-- keys: the record must survive whatever it points at. Secrets (approval tokens) are redacted before insert.
create table tool_call (
    id                uuid primary key,
    instance_id       uuid        not null,
    step_execution_id uuid,
    tool              text        not null,
    kind              text,                       -- READ, WRITE; null for a tool that has no policy
    args              jsonb       not null,
    result            text,
    status            text        not null,       -- OK, DENIED, TIMEOUT, ERROR
    idempotency_key   text,
    duration_ms       bigint      not null,
    error             text,
    created_at        timestamptz not null
);

create index tool_call_instance_idx on tool_call (instance_id);
create index tool_call_step_execution_idx on tool_call (step_execution_id);
