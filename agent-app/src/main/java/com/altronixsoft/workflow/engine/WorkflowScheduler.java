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

    /** How many rounds one wait can have: the first timer and the one after a follow-up reminder. */
    static final int MAX_ROUNDS = 2;

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
        scheduleTimeout(instanceId, state, 1, at);
    }

    /**
     * A later round of the same wait (the wait after a follow-up reminder). It needs its own id: it is set while
     * the first timer is still running, and db-scheduler removes that one when it completes.
     */
    public void scheduleTimeout(UUID instanceId, QuoteState state, int round, Instant at) {
        SchedulerClient scheduler = client.getObject();
        TaskInstance<String> timer = timeoutTask.instance(timeoutKey(instanceId, state, round), state.name());
        if (!scheduler.scheduleIfNotExists(timer, at)) {
            scheduler.reschedule(timeoutId(instanceId, state, round), at);
        }
    }

    /** Cancels every round of the wait. Idempotent: a timer that already fired or was never set is not an error. */
    public void cancelTimeout(UUID instanceId, QuoteState state) {
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            try {
                client.getObject().cancel(timeoutId(instanceId, state, round));
            } catch (TaskInstanceNotFoundException alreadyGone) {
                // nothing to cancel
            }
        }
    }

    public boolean isAdvanceScheduled(UUID instanceId) {
        return client.getObject()
                .getScheduledExecution(TaskInstanceId.of(SchedulerTasks.ADVANCE, instanceId.toString()))
                .isPresent();
    }

    public boolean isTimeoutScheduled(UUID instanceId, QuoteState state) {
        return isTimeoutScheduled(instanceId, state, 1);
    }

    public boolean isTimeoutScheduled(UUID instanceId, QuoteState state, int round) {
        return client.getObject()
                .getScheduledExecution(timeoutId(instanceId, state, round))
                .isPresent();
    }

    private static TaskInstanceId timeoutId(UUID instanceId, QuoteState state, int round) {
        return TaskInstanceId.of(SchedulerTasks.TIMEOUT, timeoutKey(instanceId, state, round));
    }

    /** Round 1 keeps the original form {@code instanceId:STATE}; later rounds add {@code :N}. */
    private static String timeoutKey(UUID instanceId, QuoteState state, int round) {
        return instanceId + ":" + state.name() + (round == 1 ? "" : ":" + round);
    }
}
