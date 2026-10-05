-- The model's short summary of an approval task, made on first view and kept: one model call per task.
alter table approval_task add column summary text;
