package com.altronixsoft.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(ScriptedSteps.class)
class EngineScenariosIT {

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    StepExecutionRepository executions;

    @Autowired
    WorkflowScheduler scheduler;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @BeforeEach
    void resetScript() {
        ScriptedSteps.reset();
    }

    private UUID start() {
        return engine.start("key-" + UUID.randomUUID(), TestData.simpleContext())
                .orElseThrow();
    }

    private QuoteState stateOf(UUID id) {
        return instances.findById(id).orElseThrow().getState();
    }

    private void awaitState(UUID id, QuoteState expected) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> stateOf(id) == expected);
    }

    private List<StepExecution> history(UUID id) {
        return executions.findByInstanceIdOrderByStartedAtAsc(id);
    }

    @Test
    void anInstanceWalksEveryStepToClosed() {
        UUID id = start();

        awaitState(id, QuoteState.CLOSED);

        assertThat(history(id))
                .extracting(StepExecution::getStep)
                .containsExactly("RECEIVED", "UNDERSTOOD", "ENRICHED", "PRICED", "APPROVED", "RESPONDED");
        assertThat(history(id)).extracting(StepExecution::getStatus).containsOnly(StepExecutionStatus.SUCCEEDED);
        QuoteContext ctx = instances.findById(id).orElseThrow().getContext();
        assertThat(ctx.hasFlag("passed-RECEIVED")).isTrue();
        assertThat(ctx.hasFlag("passed-RESPONDED")).isTrue();
    }

    @Test
    void startingTheSameBusinessKeyTwiceCreatesOneInstance() {
        String key = "dup-" + UUID.randomUUID();

        assertThat(engine.start(key, TestData.simpleContext())).isPresent();
        assertThat(engine.start(key, TestData.simpleContext())).isEmpty();

        assertThat(instances.findByWorkflowTypeAndBusinessKey(WorkflowEngine.WORKFLOW_TYPE, key))
                .isPresent();
    }

    @Test
    void aFailingStepIsRetriedAndThenSucceeds() {
        int[] failuresLeft = {2};
        ScriptedSteps.on(QuoteState.ENRICHED, ctx -> {
            if (failuresLeft[0]-- > 0) {
                return new StepResult.Fail("crm timeout", true);
            }
            return new StepResult.Next(QuoteState.PRICED, ctx);
        });

        UUID id = start();

        awaitState(id, QuoteState.CLOSED);
        List<StepExecution> enrich =
                history(id).stream().filter(e -> e.getStep().equals("ENRICHED")).toList();
        assertThat(enrich)
                .extracting(StepExecution::getStatus)
                .containsExactly(StepExecutionStatus.FAILED, StepExecutionStatus.FAILED, StepExecutionStatus.SUCCEEDED);
        assertThat(enrich.get(0).getError()).isEqualTo("crm timeout");
        assertThat(enrich).extracting(StepExecution::getAttempt).containsExactly(1, 2, 3);
    }

    @Test
    void aStepThatKeepsFailingEndsInExceptionAfterThreeAttempts() {
        ScriptedSteps.on(QuoteState.UNDERSTOOD, ctx -> {
            throw new IllegalStateException("model is down");
        });

        UUID id = start();

        awaitState(id, QuoteState.EXCEPTION);
        assertThat(history(id).stream().filter(e -> e.getStep().equals("UNDERSTOOD")))
                .hasSize(3)
                .extracting(StepExecution::getStatus)
                .containsOnly(StepExecutionStatus.FAILED);
        assertThat(instances.findById(id).orElseThrow().getContext().error()).contains("model is down");
    }

    @Test
    void aNonRetryableFailureEndsInExceptionAtOnce() {
        ScriptedSteps.on(QuoteState.RECEIVED, ctx -> {
            throw new NonRetryableStepException("unparseable email");
        });

        UUID id = start();

        awaitState(id, QuoteState.EXCEPTION);
        assertThat(history(id)).hasSize(1);
    }

    @Test
    void aWaitingInstanceResumesWhenTheSignalArrives() {
        ScriptedSteps.on(
                QuoteState.RECEIVED,
                ctx -> ctx.replies().isEmpty()
                        ? new StepResult.Wait(QuoteState.AWAIT_REPLY, Duration.ofHours(1), ctx)
                        : new StepResult.Next(QuoteState.UNDERSTOOD, ctx));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_REPLY);
        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.AWAIT_REPLY)).isTrue();

        engine.signal(id, new Signal.CustomerReplied("8 pallets, 4800 kg"));

        awaitState(id, QuoteState.CLOSED);
        assertThat(instances.findById(id).orElseThrow().getContext().replies()).containsExactly("8 pallets, 4800 kg");
        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.AWAIT_REPLY)).isFalse();
    }

    @Test
    void aTimeoutThatArrivesAfterTheSignalChangesNothing() {
        ScriptedSteps.on(
                QuoteState.RECEIVED,
                ctx -> ctx.replies().isEmpty()
                        ? new StepResult.Wait(QuoteState.AWAIT_REPLY, Duration.ofHours(1), ctx)
                        : new StepResult.Next(QuoteState.UNDERSTOOD, ctx));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_REPLY);
        engine.signal(id, new Signal.CustomerReplied("here is the weight"));
        awaitState(id, QuoteState.CLOSED);

        engine.onTimeout(id, QuoteState.AWAIT_REPLY);

        QuoteContext ctx = instances.findById(id).orElseThrow().getContext();
        assertThat(ctx.closeReason()).isNull();
        assertThat(stateOf(id)).isEqualTo(QuoteState.CLOSED);
    }

    @Test
    void aTimeoutClosesAnInstanceThatNobodyAnsweredFor() {
        ScriptedSteps.on(
                QuoteState.RECEIVED, ctx -> new StepResult.Wait(QuoteState.AWAIT_REPLY, Duration.ofMillis(200), ctx));

        UUID id = start();

        awaitState(id, QuoteState.CLOSED);
        assertThat(instances.findById(id).orElseThrow().getContext().closeReason())
                .isEqualTo("NO_RESPONSE");
    }

    @Test
    void approvalCarriesTheTokenAndTheApproversPriceIntoTheContext() {
        QuoteContext withQuote = TestData.fullContext();
        ScriptedSteps.on(QuoteState.PRICED, ctx -> new StepResult.Wait(QuoteState.AWAIT_APPROVAL, null, ctx));
        UUID id = engine.start("approve-" + UUID.randomUUID(), withQuote).orElseThrow();
        awaitState(id, QuoteState.AWAIT_APPROVAL);

        engine.signal(id, new Signal.Approved("tok-1", new BigDecimal("1300.00")));

        awaitState(id, QuoteState.CLOSED);
        QuoteContext ctx = instances.findById(id).orElseThrow().getContext();
        assertThat(ctx.approvalToken()).isEqualTo("tok-1");
        assertThat(ctx.quote().price()).isEqualByComparingTo("1300.00");
        assertThat(ctx.quote().marginPct()).isEqualByComparingTo("23.08");
    }

    @Test
    void rejectionClosesTheInstanceWithAReason() {
        ScriptedSteps.on(QuoteState.PRICED, ctx -> new StepResult.Wait(QuoteState.AWAIT_APPROVAL, null, ctx));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_APPROVAL);

        engine.signal(id, new Signal.Rejected("too cheap"));

        assertThat(stateOf(id)).isEqualTo(QuoteState.CLOSED);
        assertThat(instances.findById(id).orElseThrow().getContext().closeReason())
                .isEqualTo("REJECTED");
    }

    @Test
    void retryReturnsToTheChosenStepAndClearsTheError() {
        ScriptedSteps.on(
                QuoteState.PRICED,
                ctx -> ctx.hasFlag("retried")
                        ? new StepResult.Next(QuoteState.APPROVED, ctx)
                        : new StepResult.Wait(QuoteState.AWAIT_APPROVAL, null, ctx.withError("NO_RATES")));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_APPROVAL);
        ScriptedSteps.on(QuoteState.ENRICHED, ctx -> new StepResult.Next(QuoteState.PRICED, ctx.withFlag("retried")));

        engine.signal(id, new Signal.Retry(QuoteState.ENRICHED));

        awaitState(id, QuoteState.CLOSED);
        assertThat(instances.findById(id).orElseThrow().getContext().error()).isNull();
    }

    @Test
    void aReplyAfterTheQuoteWasSentClosesTheInstanceForAHuman() {
        ScriptedSteps.on(
                QuoteState.RESPONDED, ctx -> new StepResult.Wait(QuoteState.FOLLOW_UP, Duration.ofHours(1), ctx));
        UUID id = start();
        awaitState(id, QuoteState.FOLLOW_UP);

        engine.signal(id, new Signal.CustomerReplied("accepted"));

        assertThat(stateOf(id)).isEqualTo(QuoteState.CLOSED);
        assertThat(instances.findById(id).orElseThrow().getContext().closeReason())
                .isEqualTo("CUSTOMER_REPLIED");
    }

    @Test
    void aSignalThatDoesNotFitTheStateIsRefusedAndChangesNothing() {
        ScriptedSteps.on(
                QuoteState.RECEIVED, ctx -> new StepResult.Wait(QuoteState.AWAIT_REPLY, Duration.ofHours(1), ctx));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_REPLY);

        assertThatThrownBy(() -> engine.signal(id, new Signal.Rejected("no")))
                .isInstanceOf(InvalidSignalException.class);
        assertThatThrownBy(() -> engine.signal(UUID.randomUUID(), new Signal.Rejected("no")))
                .isInstanceOf(InstanceNotFoundException.class);

        assertThat(stateOf(id)).isEqualTo(QuoteState.AWAIT_REPLY);
    }

    @Test
    void retryFromAnInvalidStepIsRefused() {
        ScriptedSteps.on(QuoteState.PRICED, ctx -> new StepResult.Wait(QuoteState.AWAIT_APPROVAL, null, ctx));
        UUID id = start();
        awaitState(id, QuoteState.AWAIT_APPROVAL);

        assertThatThrownBy(() -> engine.signal(id, new Signal.Retry(QuoteState.APPROVED)))
                .isInstanceOf(InvalidSignalException.class);
        assertThat(stateOf(id)).isEqualTo(QuoteState.AWAIT_APPROVAL);
    }

    @Test
    void aRunThatDiedMidStepIsMarkedAbandonedAndTheStepRunsAgain() {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            instances.saveAndFlush(new WorkflowInstance(
                    id,
                    WorkflowEngine.WORKFLOW_TYPE,
                    "dead-" + id,
                    QuoteState.UNDERSTOOD,
                    TestData.simpleContext(),
                    clock.instant()));
            // what a killed process leaves behind: a RUNNING row that will never finish
            executions.saveAndFlush(StepExecution.started(
                    UUID.randomUUID(), id, "UNDERSTOOD", 1, TestData.simpleContext(), clock.instant()));
        });

        engine.advance(id);

        assertThat(stateOf(id)).isEqualTo(QuoteState.CLOSED);
        List<StepExecution> understood = history(id).stream()
                .filter(e -> e.getStep().equals("UNDERSTOOD"))
                .toList();
        assertThat(understood)
                .extracting(StepExecution::getStatus)
                .containsExactly(StepExecutionStatus.ABANDONED, StepExecutionStatus.SUCCEEDED);
        assertThat(understood).extracting(StepExecution::getAttempt).containsExactly(1, 2);
    }

    @Test
    void aTimerSetInARolledBackTransactionDoesNotSurvive() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    scheduler.scheduleTimeout(
                            id, QuoteState.AWAIT_REPLY, clock.instant().plusSeconds(3600));
                    throw new IllegalStateException("rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scheduler.isTimeoutScheduled(id, QuoteState.AWAIT_REPLY)).isFalse();
    }

    @Test
    void stepsSeeTheirInstanceThroughTheScope() {
        UUID[] seen = new UUID[1];
        ScriptedSteps.on(QuoteState.RECEIVED, ctx -> {
            seen[0] = StepScope.current().orElseThrow().instanceId();
            return new StepResult.Next(QuoteState.CLOSED, ctx);
        });

        UUID id = start();

        awaitState(id, QuoteState.CLOSED);
        assertThat(seen[0]).isEqualTo(id);
    }
}
