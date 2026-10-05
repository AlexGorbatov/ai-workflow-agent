package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The two db-scheduler tasks the engine runs on: "advance this instance" and "this wait timed out". */
@Configuration(proxyBeanMethods = false)
class SchedulerTasks {

    static final String ADVANCE = "advance-instance";
    static final String TIMEOUT = "wait-timeout";

    /** The id is the instance id, or {@code instanceId:retry-N} for the n-th retry. */
    @Bean
    OneTimeTask<Void> advanceTask(ObjectProvider<InstanceRunner> runner) {
        return Tasks.oneTime(ADVANCE)
                .onFailureRetryLater()
                .execute((instance, ctx) -> runner.getObject()
                        .advance(UUID.fromString(instance.getId().split(":")[0])));
    }

    /** The instance id is {@code instanceId:STATE}; the data is the state the timer was set for. */
    @Bean
    OneTimeTask<String> timeoutTask(ObjectProvider<InstanceRunner> runner) {
        return Tasks.oneTime(TIMEOUT, String.class).execute((instance, ctx) -> {
            UUID instanceId = UUID.fromString(instance.getId().split(":")[0]);
            runner.getObject().onTimeout(instanceId, QuoteState.valueOf(instance.getData()));
        });
    }
}
