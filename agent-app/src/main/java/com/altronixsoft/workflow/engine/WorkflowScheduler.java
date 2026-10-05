package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The engine's view of db-scheduler. The client comes through an {@link ObjectProvider} so the engine
 * does not depend on it at creation time (the tasks call back into the engine).
 */
@Component
public class WorkflowScheduler {

    private final ObjectProvider<SchedulerClient> client;
    private final OneTimeTask<Void> advanceTask;
    private final OneTimeTask<String> timeoutTask;

    WorkflowScheduler(
            ObjectProvider<SchedulerClient> client, OneTimeTask<Void> advanceTask, OneTimeTask<String> timeoutTask) {
        this.client = client;
        this.advanceTask = advanceTask;
        this.timeoutTask = timeoutTask;
    }

    /** Idempotent: a second call for the same instance leaves the first scheduled run alone. */
    public void scheduleAdvance(UUID instanceId, Instant at) {
        client.getObject().scheduleIfNotExists(advanceTask.instance(instanceId.toString()), at);
    }

    /**
     * A retry gets its own task id: the run that failed still owns {@code instanceId} in db-scheduler, so
     * scheduling under the same id would be dropped.
     */
    public void scheduleRetry(UUID instanceId, int attempt, Instant at) {
        client.getObject().scheduleIfNotExists(advanceTask.instance(instanceId + ":retry-" + attempt), at);
    }

    /** Replaces an earlier timer for the same instance and state. */
    public void scheduleTimeout(UUID instanceId, QuoteState state, Instant at) {
        SchedulerClient scheduler = client.getObject();
        TaskInstance<String> timer = timeoutTask.instance(timeoutKey(instanceId, state), state.name());
        if (!scheduler.scheduleIfNotExists(timer, at)) {
            scheduler.reschedule(timeoutId(instanceId, state), at);
        }
    }

    /** Idempotent: a timer that already fired or was never set is not an error. */
    public void cancelTimeout(UUID instanceId, QuoteState state) {
        try {
            client.getObject().cancel(timeoutId(instanceId, state));
        } catch (TaskInstanceNotFoundException alreadyGone) {
            // nothing to cancel
        }
    }

    public boolean isAdvanceScheduled(UUID instanceId) {
        return client.getObject()
                .getScheduledExecution(TaskInstanceId.of(SchedulerTasks.ADVANCE, instanceId.toString()))
                .isPresent();
    }

    public boolean isTimeoutScheduled(UUID instanceId, QuoteState state) {
        return client.getObject()
                .getScheduledExecution(timeoutId(instanceId, state))
                .isPresent();
    }

    private static TaskInstanceId timeoutId(UUID instanceId, QuoteState state) {
        return TaskInstanceId.of(SchedulerTasks.TIMEOUT, timeoutKey(instanceId, state));
    }

    private static String timeoutKey(UUID instanceId, QuoteState state) {
        return instanceId + ":" + state.name();
    }
}
