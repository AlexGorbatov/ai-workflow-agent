package com.altronixsoft.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@IntegrationTest
@Import(SchedulerTasksIT.RecordingRunner.class)
class SchedulerTasksIT {

    record Timeout(UUID id, QuoteState state) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingRunner {

        static final List<UUID> advanced = new CopyOnWriteArrayList<>();
        static final List<Timeout> timeouts = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        InstanceRunner instanceRunner() {
            return new InstanceRunner() {
                @Override
                public void advance(UUID instanceId) {
                    advanced.add(instanceId);
                }

                @Override
                public void onTimeout(UUID instanceId, QuoteState expected) {
                    timeouts.add(new Timeout(instanceId, expected));
                }
            };
        }
    }

    @Autowired
    WorkflowScheduler scheduler;

    @Test
    void anAdvanceTaskReachesTheRunner() {
        UUID id = UUID.randomUUID();

        scheduler.scheduleAdvance(id, Instant.now());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(RecordingRunner.advanced).contains(id));
    }

    @Test
    void schedulingTheSameAdvanceTwiceIsHarmless() {
        UUID id = UUID.randomUUID();

        scheduler.scheduleAdvance(id, Instant.now().plus(Duration.ofHours(1)));
        scheduler.scheduleAdvance(id, Instant.now().plus(Duration.ofHours(1)));

        assertThat(scheduler.isAdvanceScheduled(id)).isTrue();
    }

    @Test
    void aTimeoutCarriesTheExpectedStateToTheRunner() {
        UUID id = UUID.randomUUID();

        scheduler.scheduleTimeout(id, QuoteState.AWAIT_REPLY, Instant.now());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(RecordingRunner.timeouts).contains(new Timeout(id, QuoteState.AWAIT_REPLY)));
    }

    @Test
    void aCancelledTimeoutNeverFires() {
        UUID id = UUID.randomUUID();
        scheduler.scheduleTimeout(id, QuoteState.FOLLOW_UP, Instant.now().plus(Duration.ofHours(1)));
        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.FOLLOW_UP)).isTrue();

        scheduler.cancelTimeout(id, QuoteState.FOLLOW_UP);

        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.FOLLOW_UP)).isFalse();
    }

    @Test
    void aTimeoutIsKeyedByInstanceAndStateSoTwoStatesCoexist() {
        UUID id = UUID.randomUUID();
        scheduler.scheduleTimeout(id, QuoteState.AWAIT_REPLY, Instant.now().plus(Duration.ofHours(1)));
        scheduler.scheduleTimeout(id, QuoteState.FOLLOW_UP, Instant.now().plus(Duration.ofHours(1)));

        scheduler.cancelTimeout(id, QuoteState.AWAIT_REPLY);

        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.AWAIT_REPLY)).isFalse();
        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.FOLLOW_UP)).isTrue();
    }

    @Test
    void cancellingATimerThatIsNotThereIsHarmless() {
        scheduler.cancelTimeout(UUID.randomUUID(), QuoteState.AWAIT_APPROVAL);
    }

    @Test
    void reschedulingATimerMovesItInsteadOfDuplicating() {
        UUID id = UUID.randomUUID();
        scheduler.scheduleTimeout(id, QuoteState.AWAIT_REPLY, Instant.now().plus(Duration.ofHours(1)));

        scheduler.scheduleTimeout(id, QuoteState.AWAIT_REPLY, Instant.now());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(RecordingRunner.timeouts).contains(new Timeout(id, QuoteState.AWAIT_REPLY)));
    }
}
