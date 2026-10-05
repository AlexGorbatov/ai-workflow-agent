package com.altronixsoft.workflow.approval;

import static com.altronixsoft.workflow.TestJwt.MAX;
import static com.altronixsoft.workflow.TestJwt.OLENA;
import static com.altronixsoft.workflow.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.TestJwt;
import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.engine.EnteredWaitState;
import com.altronixsoft.workflow.engine.ScriptedSteps;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.outbox.OutboxRepository;
import com.altronixsoft.workflow.quote.PolicyDecision;
import com.altronixsoft.workflow.quote.Quote;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approvals end to end on the real engine with scripted steps: PRICED waits for a person with a quote and the
 * policy's reasons. Decisions go through the API as the users of keycloak/workflow-realm.json.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({ScriptedSteps.class, TestJwt.class})
class ApprovalFlowIT {

    private static final Duration SLA = Duration.ofHours(24);
    private static final BigDecimal COST = new BigDecimal("1000.00");
    private static final BigDecimal PRICE = new BigDecimal("1318.00");

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    ApprovalTaskRepository tasks;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ApprovalSla sla;

    @Autowired
    ApprovalTokens tokens;

    @Autowired
    StubChatModel model;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        ScriptedSteps.reset();
        model.reset();
    }

    /** An instance that reached AWAIT_APPROVAL through the engine, as the Policy step would leave it. */
    private UUID waitingForApproval() {
        ScriptedSteps.on(
                QuoteState.PRICED,
                ctx -> new StepResult.Wait(
                        QuoteState.AWAIT_APPROVAL,
                        SLA,
                        ctx.withQuote(new Quote(
                                        "EuroLine", COST, PRICE, Quote.marginPct(COST, PRICE), "EUR", null, List.of()))
                                .withPolicy(new PolicyDecision(false, List.of("flag:NEW_CUSTOMER")))));
        UUID id = engine.start("key-" + UUID.randomUUID(), QuoteContext.of(SampleEmails.read("03-new-customer.eml")))
                .orElseThrow();
        awaitState(id, QuoteState.AWAIT_APPROVAL);
        return id;
    }

    private void awaitState(UUID id, QuoteState state) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> instance(id).getState() == state);
    }

    private WorkflowInstance instance(UUID id) {
        return instances.findById(id).orElseThrow();
    }

    private List<ApprovalTask> tasksOf(UUID instanceId) {
        return tasks.findByInstanceIdOrderByCreatedAtAsc(instanceId);
    }

    private ApprovalTask onlyTask(UUID instanceId) {
        assertThat(tasksOf(instanceId)).hasSize(1);
        return tasksOf(instanceId).getFirst();
    }

    private ResultActions decide(UUID taskId, String user, String json) throws Exception {
        return mvc.perform(post("/api/v1/approvals/{id}/decision", taskId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    // --- T5.1: the task

    @Test
    void waitingForApprovalOpensATaskWithThePolicyReasonsAndAnSlaTimer() {
        UUID id = waitingForApproval();

        ApprovalTask task = onlyTask(id);
        assertThat(task.getKind()).isEqualTo(ApprovalKind.QUOTE);
        assertThat(task.getRound()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(ApprovalStatus.OPEN);
        assertThat(task.getReasons()).containsExactly("flag:NEW_CUSTOMER");
        assertThat(Duration.between(task.getCreatedAt(), task.getDueAt())).isEqualTo(SLA);
        Integer timers = jdbc.sql(
                        "select count(*) from scheduled_tasks where task_name = 'approval-sla' and task_instance = :id")
                .param("id", task.getId().toString())
                .query(Integer.class)
                .single();
        assertThat(timers).isEqualTo(1);
    }

    @Test
    void theSameEventTwiceOpensOneTask() {
        UUID id = waitingForApproval();

        tx.executeWithoutResult(s -> events.publishEvent(
                new EnteredWaitState(id, QuoteState.AWAIT_APPROVAL, instance(id).getContext())));

        assertThat(tasksOf(id)).hasSize(1);
    }

    @Test
    void anInvestigationOpensAnInvestigationTask() {
        ScriptedSteps.on(
                QuoteState.ENRICHED, ctx -> new StepResult.Next(QuoteState.INVESTIGATING, ctx.withError("NO_RATES")));
        ScriptedSteps.on(QuoteState.INVESTIGATING, ctx -> new StepResult.Wait(QuoteState.AWAIT_APPROVAL, SLA, ctx));
        UUID id = engine.start("key-" + UUID.randomUUID(), QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml")))
                .orElseThrow();
        awaitState(id, QuoteState.AWAIT_APPROVAL);

        ApprovalTask task = onlyTask(id);
        assertThat(task.getKind()).isEqualTo(ApprovalKind.INVESTIGATION);
        assertThat(task.getReasons()).containsExactly("investigation:NO_RATES");
    }

    @Test
    void aRetryThatEndsInApprovalAgainOpensTheNextRound() throws Exception {
        UUID id = waitingForApproval();
        UUID first = onlyTask(id).getId();

        decide(first, MAX, """
                {"action":"RETRY","retryFrom":"ENRICHED","comment":"rates looked stale"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRIED"));

        await().atMost(10, TimeUnit.SECONDS).until(() -> tasksOf(id).size() == 2);
        List<ApprovalTask> all = tasksOf(id);
        assertThat(all).extracting(ApprovalTask::getRound).containsExactly(1, 2);
        assertThat(all)
                .extracting(ApprovalTask::getStatus)
                .containsExactly(ApprovalStatus.RETRIED, ApprovalStatus.OPEN);
        assertThat(instance(id).getState()).isEqualTo(QuoteState.AWAIT_APPROVAL);
    }

    // --- T5.3: decisions

    @Test
    void anApproverApprovesWithAnEditedPriceAndTheInstanceGetsATokenForIt() throws Exception {
        ScriptedSteps.on(
                QuoteState.APPROVED, ctx -> new StepResult.Next(QuoteState.CLOSED, ctx.withCloseReason("TEST")));
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();

        decide(taskId, MAX, """
                {"action":"APPROVE","editedPrice":1250.00,"comment":"loyal customer"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("max"))
                .andExpect(jsonPath("$.approvedPrice").value(1250.00));

        awaitState(id, QuoteState.CLOSED);
        QuoteContext ctx = instance(id).getContext();
        assertThat(ctx.quote().price()).isEqualByComparingTo("1250.00");
        ApprovalClaims claims =
                tokens.verify(ctx.approvalToken(), ApprovalActions.CREATE_OPPORTUNITY, new BigDecimal("1250.00"));
        assertThat(claims.approvedBy()).isEqualTo("max");
        assertThat(claims.instanceId()).isEqualTo(id);
        assertThat(claims.maxAmount()).isEqualByComparingTo("1250.00");
    }

    @Test
    void aPriceBelowCostIsRejectedAndNothingChanges() throws Exception {
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();

        decide(taskId, MAX, """
                {"action":"APPROVE","editedPrice":999.99}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The price 999.99 is below the cost 1000.00"));

        assertThat(onlyTask(id).getStatus()).isEqualTo(ApprovalStatus.OPEN);
        assertThat(instance(id).getState()).isEqualTo(QuoteState.AWAIT_APPROVAL);
    }

    @Test
    void aSecondDecisionIsAConflictNotASecondSignal() throws Exception {
        ScriptedSteps.on(
                QuoteState.APPROVED, ctx -> new StepResult.Next(QuoteState.CLOSED, ctx.withCloseReason("TEST")));
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();

        decide(taskId, MAX, "{\"action\":\"APPROVE\"}").andExpect(status().isOk());
        decide(taskId, MAX, "{\"action\":\"APPROVE\"}").andExpect(status().isConflict());
        decide(taskId, MAX, "{\"action\":\"REJECT\",\"comment\":\"too late\"}").andExpect(status().isConflict());

        awaitState(id, QuoteState.CLOSED);
        assertThat(instance(id).getContext().closeReason()).isEqualTo("TEST");
        assertThat(onlyTask(id).getApprovedPrice()).isEqualByComparingTo(PRICE);
    }

    @Test
    void aRejectNeedsACommentAndClosesTheInstance() throws Exception {
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();

        decide(taskId, MAX, "{\"action\":\"REJECT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a comment is required to reject"));
        decide(taskId, MAX, "{\"action\":\"REJECT\",\"comment\":\"customer is blacklisted\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.comment").value("customer is blacklisted"));

        awaitState(id, QuoteState.CLOSED);
        assertThat(instance(id).getContext().closeReason()).isEqualTo("REJECTED");
    }

    @Test
    void aRetryCanOnlyRestartFromUnderstoodOrEnriched() throws Exception {
        UUID taskId = onlyTask(waitingForApproval()).getId();

        decide(taskId, MAX, "{\"action\":\"RETRY\",\"retryFrom\":\"PRICED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("retryFrom must be UNDERSTOOD or ENRICHED"));
        decide(taskId, MAX, "{\"action\":\"RETRY\"}").andExpect(status().isBadRequest());
    }

    @Test
    void anOperatorCannotDecide() throws Exception {
        UUID taskId = onlyTask(waitingForApproval()).getId();

        decide(taskId, OLENA, "{\"action\":\"APPROVE\"}").andExpect(status().isForbidden());

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.OPEN);
    }

    @Test
    void anUnknownTaskIsNotFound() throws Exception {
        decide(UUID.randomUUID(), MAX, "{\"action\":\"APPROVE\"}").andExpect(status().isNotFound());
    }

    @Test
    void approversSeeThePendingTasksAndTheFactsOfOne() throws Exception {
        model.whenPromptContains("flag:NEW_CUSTOMER").replyWith("A new customer asks for a quote.");
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();

        mvc.perform(get("/api/v1/approvals").header(HttpHeaders.AUTHORIZATION, bearer(MAX)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id == '%s')].price".formatted(taskId)).value(1318.00));
        mvc.perform(get("/api/v1/approvals/{id}", taskId).header(HttpHeaders.AUTHORIZATION, bearer(MAX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[0]").value("flag:NEW_CUSTOMER"))
                .andExpect(jsonPath("$.quote.cost").value(1000.00))
                .andExpect(jsonPath("$.sender").value("tomasz.nowak@nowafirma.test"))
                .andExpect(jsonPath("$.summary").value("A new customer asks for a quote."));
    }

    // --- T5.5: SLA and summary

    @Test
    void anOpenTaskPastItsSlaIsEscalatedOnceWithAMailToTheLead() {
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();
        assertThat(sla.escalateIfDue(taskId)).as("not due yet").isFalse();

        overdue(taskId);

        assertThat(sla.escalateIfDue(taskId)).isTrue();
        assertThat(sla.escalateIfDue(taskId)).as("only once").isFalse();
        assertThat(onlyTask(id).getStatus()).isEqualTo(ApprovalStatus.ESCALATED);
        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(id))
                .singleElement()
                .satisfies(mail -> {
                    assertThat(mail.getPayload().to()).isEqualTo("approvals-lead@nordline.test");
                    assertThat(mail.getDedupeKey()).endsWith(":ESCALATION-QUOTE-1");
                });
    }

    @Test
    void anEscalatedTaskCanStillBeDecided() throws Exception {
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();
        overdue(taskId);
        sla.escalateIfDue(taskId);

        decide(taskId, MAX, "{\"action\":\"REJECT\",\"comment\":\"no capacity\"}")
                .andExpect(status().isOk());
    }

    @Test
    void aDecidedTaskIsNotEscalated() throws Exception {
        UUID id = waitingForApproval();
        UUID taskId = onlyTask(id).getId();
        decide(taskId, MAX, "{\"action\":\"REJECT\",\"comment\":\"no capacity\"}")
                .andExpect(status().isOk());
        overdue(taskId);

        assertThat(sla.escalateIfDue(taskId)).isFalse();
        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    @Test
    void theSummaryIsMadeOnceAndKept() throws Exception {
        model.whenPromptContains("flag:NEW_CUSTOMER").replyWith("A new customer asks for a quote.");
        UUID taskId = onlyTask(waitingForApproval()).getId();

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/approvals/{id}", taskId).header(HttpHeaders.AUTHORIZATION, bearer(MAX)))
                    .andExpect(jsonPath("$.summary").value("A new customer asks for a quote."));
        }

        assertThat(model.prompts()).hasSize(1);
        assertThat(model.prompts().getFirst().getContents())
                .doesNotContain("Wycena transportu")
                .doesNotContain("Dzień dobry");
        assertThat(tasks.findById(taskId).orElseThrow().getSummary()).isEqualTo("A new customer asks for a quote.");
    }

    @Test
    void aFailingModelLeavesTheSummaryEmptyAndTheScreenWorking() throws Exception {
        model.failWith(new IllegalStateException("model is down"));
        UUID taskId = onlyTask(waitingForApproval()).getId();

        mvc.perform(get("/api/v1/approvals/{id}", taskId).header(HttpHeaders.AUTHORIZATION, bearer(MAX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.reasons[0]").value("flag:NEW_CUSTOMER"));
        assertThat(tasks.findById(taskId).orElseThrow().getSummary()).isNull();
    }

    private void overdue(UUID taskId) {
        jdbc.sql("update approval_task set due_at = now() - interval '1 minute' where id = :id")
                .param("id", taskId)
                .update();
    }
}
