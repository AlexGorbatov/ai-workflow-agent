package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.engine.EnteredWaitState;
import com.altronixsoft.workflow.policy.PolicyRules;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opens an approval task when an instance starts waiting for a person. It listens synchronously, inside the
 * transaction that moves the instance: the wait and its task are committed together or not at all. The same
 * event twice opens one task; a new wait after a Retry (the previous task is decided) opens the next round.
 */
@Component
class ApprovalTaskCreator {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PolicyRules policy;
    private final ApprovalSla sla;
    private final Clock clock;

    ApprovalTaskCreator(JdbcClient jdbc, JsonMapper json, PolicyRules policy, ApprovalSla sla, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.policy = policy;
        this.sla = sla;
        this.clock = clock;
    }

    @EventListener
    void on(EnteredWaitState event) {
        if (event.state() != QuoteState.AWAIT_APPROVAL) {
            return;
        }
        QuoteContext ctx = event.ctx();
        ApprovalKind kind = ctx.error() != null ? ApprovalKind.INVESTIGATION : ApprovalKind.QUOTE;
        List<String> reasons = kind == ApprovalKind.INVESTIGATION
                ? List.of("investigation:" + ctx.error())
                : ctx.policy() == null ? List.of() : ctx.policy().reasons();
        Instant now = clock.instant();
        Instant due = now.plus(policy.approvalSla());
        UUID id = UUID.randomUUID();
        if (insertIfAbsent(id, event.instanceId(), kind, reasons, due, now)) {
            sla.schedule(id, due);
        }
    }

    /** Inserts the next round unless a task of this kind is still pending; true if a row was inserted. */
    private boolean insertIfAbsent(
            UUID id, UUID instanceId, ApprovalKind kind, List<String> reasons, Instant due, Instant now) {
        int inserted = jdbc.sql("""
                        insert into approval_task (id, instance_id, kind, round, status, reasons, due_at, created_at)
                        select :id, :instance, :kind, coalesce(max(round), 0) + 1, 'OPEN', cast(:reasons as jsonb), :due, :now
                        from approval_task
                        where instance_id = :instance and kind = :kind
                        having not exists (
                            select 1 from approval_task
                            where instance_id = :instance and kind = :kind and status in ('OPEN', 'ESCALATED'))
                        on conflict (instance_id, kind, round) do nothing
                        """)
                .param("id", id)
                .param("instance", instanceId)
                .param("kind", kind.name())
                .param("reasons", json.writeValueAsString(reasons))
                .param("due", Timestamp.from(due))
                .param("now", Timestamp.from(now))
                .update();
        return inserted == 1;
    }
}
