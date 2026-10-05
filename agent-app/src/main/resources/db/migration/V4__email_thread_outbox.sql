-- Which workflow instance a mail belongs to, for every mail in or out. A reply is matched to its
-- instance by looking up the Message-ID it answers.
create table email_thread (
    message_id  text primary key,
    instance_id uuid        not null,
    direction   text        not null,   -- IN, OUT
    created_at  timestamptz not null
);

create index email_thread_instance_idx on email_thread (instance_id);

-- Effects waiting to leave the system. dedupe_key makes enqueueing idempotent: a step that runs twice
-- queues the same mail once.
create table outbox (
    id              uuid primary key,
    instance_id     uuid        not null,
    dedupe_key      text        not null unique,
    type            text        not null,   -- EMAIL
    payload         jsonb       not null,
    status          text        not null,   -- PENDING, SENT, FAILED
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null,
    last_error      text,
    created_at      timestamptz not null,
    sent_at         timestamptz
);

create index outbox_due_idx on outbox (next_attempt_at) where status = 'PENDING';
