package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only class that changes {@link WorkflowInstance}. A step runs outside any transaction; its result
 * is applied in one short transaction, so a crash leaves either the old state or the new one.
 */
@Service
public class WorkflowEngine implements InstanceRunner {

    public static final String WORKFLOW_TYPE = "quote";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowInstanceRepository instances;
    private final StepExecutionRepository executions;
    private final StepRegistry registry;
    private final WorkflowScheduler scheduler;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final EngineProperties properties;
    private final ObjectProvider<FollowUpHandler> followUps;

    WorkflowEngine(
            WorkflowInstanceRepository instances,
            StepExecutionRepository executions,
            StepRegistry registry,
            WorkflowScheduler scheduler,
            TransactionTemplate tx,
            ApplicationEventPublisher events,
            Clock clock,
            EngineProperties properties,
            ObjectProvider<FollowUpHandler> followUps) {
        this.instances = instances;
        this.executions = executions;
        this.registry = registry;
        this.scheduler = scheduler;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
        this.followUps = followUps;
    }

    /** Creates an instance in RECEIVED and schedules it; empty if the business key already exists. */
    public Optional<UUID> start(String businessKey, QuoteContext ctx) {
        if (instances
                .findByWorkflowTypeAndBusinessKey(WORKFLOW_TYPE, businessKey)
                .isPresent()) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(status -> {
                instances.saveAndFlush(new WorkflowInstance(
                        id, WORKFLOW_TYPE, businessKey, QuoteState.RECEIVED, ctx, clock.instant()));
                scheduler.scheduleAdvance(id, clock.instant());
            });
        } catch (DataIntegrityViolationException duplicate) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    public Optional<UUID> findByBusinessKey(String businessKey) {
        return instances
                .findByWorkflowTypeAndBusinessKey(WORKFLOW_TYPE, businessKey)
                .map(WorkflowInstance::getId);
    }

    @Override
    public void advance(UUID instanceId) {
        while (true) {
            Attempt attempt = tx.execute(status -> begin(instanceId));
            if (attempt == null) {
                return;
            }
            StepResult result = run(instanceId, attempt);
            try {
                boolean more = Boolean.TRUE.equals(tx.execute(status -> apply(instanceId, attempt, result)));
                if (!more) {
                    return;
                }
            } catch (ObjectOptimisticLockingFailureException anotherNodeMovedIt) {
                log.debug("Instance {} was advanced elsewhere; dropping this result", instanceId);
                return;
            }
        }
    }

    /** Moves the instance on a signal from outside. Throws {@link InvalidSignalException} if it does not apply. */
    public void signal(UUID instanceId, Signal signal) {
        tx.executeWithoutResult(status -> {
            WorkflowInstance instance = load(instanceId);
            QuoteState from = instance.getState();
            SignalRules.Outcome outcome = SignalRules.apply(from, signal, instance.getContext());
            instance.transition(outcome.next(), outcome.ctx(), clock.instant());
            instances.saveAndFlush(instance);
            scheduler.cancelTimeout(instanceId, from);
            scheduleIfRunnable(instanceId, outcome.next());
        });
    }

    @Override
    public void onTimeout(UUID instanceId, QuoteState expected) {
        try {
            tx.executeWithoutResult(status -> {
                WorkflowInstance instance = load(instanceId);
                if (instance.getState() != expected) {
                    return; // a signal got here first
                }
                // AWAIT_APPROVAL has its own SLA handling (approvals). An unanswered quote gets one reminder
                // and a second wait; any other wait, or the second one, gives up.
                QuoteContext ctx = instance.getContext();
                FollowUpHandler handler = followUps.getIfAvailable();
                Instant now = clock.instant();
                if (expected == QuoteState.FOLLOW_UP
                        && handler != null
                        && !ctx.hasFlag(FollowUpHandler.FOLLOW_UP_SENT)) {
                    Duration wait = handler.remind(instanceId, ctx);
                    instance.transition(QuoteState.FOLLOW_UP, ctx.withFlag(FollowUpHandler.FOLLOW_UP_SENT), now);
                    instances.saveAndFlush(instance);
                    scheduler.scheduleTimeout(instanceId, QuoteState.FOLLOW_UP, 2, now.plus(wait));
                } else if (expected == QuoteState.AWAIT_REPLY || expected == QuoteState.FOLLOW_UP) {
                    instance.transition(QuoteState.CLOSED, ctx.withCloseReason("NO_RESPONSE"), now);
                    instances.saveAndFlush(instance);
                }
            });
        } catch (ObjectOptimisticLockingFailureException signalWon) {
            log.debug("Timeout for {} lost to a concurrent change", instanceId);
        }
    }

    private record Attempt(UUID executionId, int number, QuoteState state, QuoteContext ctx) {}

    private Attempt begin(UUID instanceId) {
        WorkflowInstance instance = instances.findById(instanceId).orElse(null);
        if (instance == null
                || instance.getState().isTerminal()
                || instance.getState().isWaiting()) {
            return null;
        }
        Instant now = clock.instant();
        String step = instance.getState().name();
        List<StepExecution> history = executions.findByInstanceIdOrderByStartedAtAsc(instanceId);
        history.stream()
                .filter(e -> e.getStatus() == StepExecutionStatus.RUNNING)
                .forEach(e -> e.abandon(now));
        int number = history.stream()
                        .filter(e -> e.getStep().equals(step))
                        .mapToInt(StepExecution::getAttempt)
                        .max()
                        .orElse(0)
                + 1;
        UUID executionId = UUID.randomUUID();
        executions.saveAndFlush(
                StepExecution.started(executionId, instanceId, step, number, instance.getContext(), now));
        return new Attempt(executionId, number, instance.getState(), instance.getContext());
    }

    private StepResult run(UUID instanceId, Attempt attempt) {
        try {
            return StepScope.call(
                    new StepScope.Current(instanceId, attempt.executionId()),
                    () -> registry.forState(attempt.state()).execute(instanceId, attempt.ctx()));
        } catch (NonRetryableStepException e) {
            return new StepResult.Fail(e.getMessage(), false);
        } catch (RuntimeException e) {
            log.warn("Step {} failed for instance {}", attempt.state(), instanceId, e);
            return new StepResult.Fail(String.valueOf(e.getMessage()), true);
        }
    }

    /** Applies a step result in the caller's transaction; true when the loop should run the next step. */
    private boolean apply(UUID instanceId, Attempt attempt, StepResult result) {
        WorkflowInstance instance = load(instanceId);
        StepExecution execution = executions.findById(attempt.executionId()).orElseThrow();
        Instant now = clock.instant();
        switch (result) {
            case StepResult.Next next -> {
                execution.succeed(next.ctx(), now);
                instance.transition(next.next(), next.ctx(), now);
                instances.saveAndFlush(instance);
                return !next.next().isTerminal() && !next.next().isWaiting();
            }
            case StepResult.Wait wait -> {
                execution.succeed(wait.ctx(), now);
                instance.transition(wait.waitState(), wait.ctx(), now);
                instances.saveAndFlush(instance);
                if (wait.waitState() != QuoteState.AWAIT_APPROVAL && wait.timeout() != null) {
                    scheduler.scheduleTimeout(instanceId, wait.waitState(), now.plus(wait.timeout()));
                }
                events.publishEvent(new EnteredWaitState(instanceId, wait.waitState(), wait.ctx()));
                return false;
            }
            case StepResult.Fail fail -> {
                execution.fail(fail.reason(), now);
                int failures = consecutiveFailures(instanceId, attempt);
                if (!fail.retryable() || failures >= properties.maxAttempts()) {
                    instance.transition(
                            QuoteState.EXCEPTION, instance.getContext().withError(fail.reason()), now);
                    instances.saveAndFlush(instance);
                } else {
                    instance.transition(instance.getState(), instance.getContext(), now); // bumps the version
                    instances.saveAndFlush(instance);
                    Instant retryAt = now.plus(properties.retryBackoffBase().multipliedBy(1L << failures));
                    scheduler.scheduleRetry(instanceId, attempt.number(), retryAt);
                }
                return false;
            }
        }
    }

    /** Failed attempts of this step since its last success, counting the one being applied. */
    private int consecutiveFailures(UUID instanceId, Attempt attempt) {
        List<StepExecution> history = executions.findByInstanceIdOrderByStartedAtAsc(instanceId);
        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            StepExecution e = history.get(i);
            if (!e.getStep().equals(attempt.state().name()) || e.getStatus() == StepExecutionStatus.ABANDONED) {
                continue;
            }
            if (e.getStatus() == StepExecutionStatus.SUCCEEDED) {
                break;
            }
            count++;
        }
        return count;
    }

    private void scheduleIfRunnable(UUID instanceId, QuoteState state) {
        if (!state.isTerminal() && !state.isWaiting()) {
            scheduler.scheduleAdvance(instanceId, clock.instant());
        }
    }

    private WorkflowInstance load(UUID instanceId) {
        return instances.findById(instanceId).orElseThrow(() -> new InstanceNotFoundException(instanceId));
    }
}
