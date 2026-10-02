create table workflow_instance (
                                   id            uuid primary key,
                                   workflow_type text        not null,
                                   business_key  text        not null,
                                   state         text        not null,
                                   context       jsonb       not null default '{}'::jsonb,
                                   version       bigint      not null default 0,
                                   created_at    timestamptz not null default now(),
                                   updated_at    timestamptz not null default now(),
                                   unique (workflow_type, business_key)
);

create table step_execution (
                                id          uuid primary key,
                                instance_id uuid not null references workflow_instance(id),
                                step        text not null,
                                attempt     int  not null,
                                status      text not null,          -- RUNNING, SUCCEEDED, FAILED
                                input       jsonb,
                                output      jsonb,
                                error       text,
                                started_at  timestamptz not null default now(),
                                finished_at timestamptz,
                                unique (instance_id, step, attempt)
);

create index on workflow_instance (state);
