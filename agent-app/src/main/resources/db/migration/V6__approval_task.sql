-- One row per time an instance waits for a person. A Retry sends the instance back through the flow; when it
-- waits again, that is the next round. The unique key makes creating the task idempotent.
create table approval_task (
    id             uuid primary key,
    instance_id    uuid          not null references workflow_instance (id),
    kind           text          not null,   -- QUOTE, INVESTIGATION
    round          int           not null,
    status         text          not null,   -- OPEN, ESCALATED, APPROVED, REJECTED, RETRIED
    reasons        jsonb         not null,
    due_at         timestamptz   not null,
    created_at     timestamptz   not null,
    decided_by     text,
    decided_at     timestamptz,
    comment        text,
    approved_price numeric(12, 2),
    version        bigint        not null default 0,
    constraint approval_task_instance_kind_round_key unique (instance_id, kind, round)
);

create index approval_task_status_idx on approval_task (status);
