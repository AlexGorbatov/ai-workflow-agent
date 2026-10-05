package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.outbox.OutboxService;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SLA of an approval task: a db-scheduler task {@code approval-sla} per approval task, due when the SLA
 * ends. If nobody has decided by then, the task is marked ESCALATED and its lead gets a mail through the outbox.
 * A task decided in time is left alone; so is a timer that fires early.
 */
@Component
public class ApprovalSla {

    static final String TASK = "approval-sla";

    private final ObjectProvider<SchedulerClient> scheduler;
    private final ObjectProvider<OneTimeTask<Void>> slaTask;
    private final ApprovalTaskRepository tasks;
    private final OutboxService outbox;
    private final ApprovalProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    ApprovalSla(
            ObjectProvider<SchedulerClient> scheduler,
            @Qualifier("approvalSlaTask") ObjectProvider<OneTimeTask<Void>> slaTask,
            ApprovalTaskRepository tasks,
            OutboxService outbox,
            ApprovalProperties properties,
            TransactionTemplate tx,
            Clock clock) {
        this.scheduler = scheduler;
        this.slaTask = slaTask;
        this.tasks = tasks;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    void schedule(UUID taskId, Instant due) {
        scheduler.getObject().scheduleIfNotExists(slaTask.getObject().instance(taskId.toString()), due);
    }

    /** Escalates the task if it is still open and due; true if it did. */
    public boolean escalateIfDue(UUID taskId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            ApprovalTask task = tasks.findById(taskId).orElse(null);
            if (task == null
                    || task.getStatus() != ApprovalStatus.OPEN
                    || task.getDueAt().isAfter(clock.instant())) {
                return false;
            }
            task.escalate();
            tasks.saveAndFlush(task);
            outbox.enqueueEmail(
                    task.getInstanceId(),
                    "ESCALATION-" + task.getKind() + "-" + task.getRound(),
                    properties.escalationTo(),
                    "Approval overdue: instance " + task.getInstanceId(),
                    "An approval task is still open past its SLA.\n\n"
                            + "Task: " + task.getId() + "\n"
                            + "Kind: " + task.getKind() + "\n"
                            + "Reasons: " + String.join(", ", task.getReasons()) + "\n"
                            + "Due: " + task.getDueAt() + "\n",
                    null);
            return true;
        }));
    }

    @Configuration(proxyBeanMethods = false)
    static class Schedule {

        @Bean
        OneTimeTask<Void> approvalSlaTask(ObjectProvider<ApprovalSla> sla) {
            return Tasks.oneTime(TASK)
                    .execute((instance, ctx) -> sla.getObject().escalateIfDue(UUID.fromString(instance.getId())));
        }
    }
}
