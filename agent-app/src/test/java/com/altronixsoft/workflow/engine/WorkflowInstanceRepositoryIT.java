package com.altronixsoft.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class WorkflowInstanceRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    StepExecutionRepository executions;

    @Autowired
    TransactionTemplate tx;

    private WorkflowInstance newInstance(String businessKey, QuoteContext ctx) {
        return new WorkflowInstance(UUID.randomUUID(), "quote", businessKey, QuoteState.RECEIVED, ctx, NOW);
    }

    @Test
    void everyFieldOfTheContextSurvivesTheDatabase() {
        QuoteContext ctx = TestData.fullContext();
        WorkflowInstance saved = instances.saveAndFlush(newInstance("full-" + UUID.randomUUID(), ctx));

        WorkflowInstance loaded = instances.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getContext()).isEqualTo(ctx);
        assertThat(loaded.getState()).isEqualTo(QuoteState.RECEIVED);
        assertThat(loaded.getWorkflowType()).isEqualTo("quote");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void aTransitionIsPersistedAndBumpsTheVersion() {
        WorkflowInstance saved =
                instances.saveAndFlush(newInstance("move-" + UUID.randomUUID(), TestData.simpleContext()));
        long before = saved.getVersion();
        QuoteContext next = saved.getContext().withFlag("NEW_CUSTOMER");

        tx.executeWithoutResult(status -> {
            WorkflowInstance row = instances.findById(saved.getId()).orElseThrow();
            row.transition(QuoteState.UNDERSTOOD, next, NOW.plusSeconds(5));
        });

        WorkflowInstance loaded = instances.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(QuoteState.UNDERSTOOD);
        assertThat(loaded.getContext().hasFlag("NEW_CUSTOMER")).isTrue();
        assertThat(loaded.getVersion()).isGreaterThan(before);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void aStaleWriterLosesWithAnOptimisticLockFailure() {
        WorkflowInstance saved =
                instances.saveAndFlush(newInstance("lock-" + UUID.randomUUID(), TestData.simpleContext()));
        WorkflowInstance stale = instances.findById(saved.getId()).orElseThrow();

        tx.executeWithoutResult(status -> instances
                .findById(saved.getId())
                .orElseThrow()
                .transition(QuoteState.UNDERSTOOD, stale.getContext(), NOW.plusSeconds(1)));

        stale.transition(QuoteState.CLOSED, stale.getContext(), NOW.plusSeconds(2));
        assertThatThrownBy(() -> instances.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void aBusinessKeyIsUniquePerWorkflowType() {
        String key = "dup-" + UUID.randomUUID();
        instances.saveAndFlush(newInstance(key, TestData.simpleContext()));

        assertThatThrownBy(() -> instances.saveAndFlush(newInstance(key, TestData.simpleContext())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(instances.findByWorkflowTypeAndBusinessKey("quote", key)).isPresent();
        assertThat(instances.findByWorkflowTypeAndBusinessKey("quote", "absent-" + key))
                .isEmpty();
    }

    @Test
    void stepExecutionsKeepInputOutputAndStatus() {
        WorkflowInstance instance =
                instances.saveAndFlush(newInstance("steps-" + UUID.randomUUID(), TestData.simpleContext()));
        QuoteContext in = instance.getContext();
        QuoteContext out = in.withFlag("X");

        StepExecution first = StepExecution.started(UUID.randomUUID(), instance.getId(), "UNDERSTOOD", 1, in, NOW);
        executions.saveAndFlush(first);
        StepExecution second =
                StepExecution.started(UUID.randomUUID(), instance.getId(), "UNDERSTOOD", 2, in, NOW.plusSeconds(1));
        executions.saveAndFlush(second);

        tx.executeWithoutResult(
                status -> executions.findById(first.getId()).orElseThrow().abandon(NOW.plusSeconds(3)));
        tx.executeWithoutResult(
                status -> executions.findById(second.getId()).orElseThrow().succeed(out, NOW.plusSeconds(4)));

        List<StepExecution> all = executions.findByInstanceIdOrderByStartedAtAsc(instance.getId());
        assertThat(all).extracting(StepExecution::getAttempt).containsExactly(1, 2);
        assertThat(all)
                .extracting(StepExecution::getStatus)
                .containsExactly(StepExecutionStatus.ABANDONED, StepExecutionStatus.SUCCEEDED);
        assertThat(all.get(1).getInput()).isEqualTo(in);
        assertThat(all.get(1).getOutput()).isEqualTo(out);
        assertThat(all.get(1).getFinishedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(executions.findByInstanceIdAndStatus(instance.getId(), StepExecutionStatus.RUNNING))
                .isEmpty();
    }

    @Test
    void anAttemptNumberIsUniquePerInstanceAndStep() {
        WorkflowInstance instance =
                instances.saveAndFlush(newInstance("attempt-" + UUID.randomUUID(), TestData.simpleContext()));
        executions.saveAndFlush(StepExecution.started(
                UUID.randomUUID(), instance.getId(), "UNDERSTOOD", 1, instance.getContext(), NOW));

        assertThatThrownBy(() -> executions.saveAndFlush(StepExecution.started(
                        UUID.randomUUID(), instance.getId(), "UNDERSTOOD", 1, instance.getContext(), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
