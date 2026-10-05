package com.altronixsoft.workflow.api;

import static com.altronixsoft.workflow.TestJwt.EVE;
import static com.altronixsoft.workflow.TestJwt.IVAN;
import static com.altronixsoft.workflow.TestJwt.OLENA;
import static com.altronixsoft.workflow.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.TestJwt;
import com.altronixsoft.workflow.engine.ScriptedSteps;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The instances list and the timeline, on the real engine with scripted steps. */
@IntegrationTest
@AutoConfigureMockMvc
@Import({ScriptedSteps.class, TestJwt.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class InstanceApiIT {

    private static final String SECRET_PROMPT = "PROMPT TEXT THAT MUST NOT LEAK";

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EntityManagerFactory emf;

    @BeforeEach
    void reset() {
        ScriptedSteps.reset();
    }

    private UUID closedInstance() {
        UUID id = engine.start("key-" + UUID.randomUUID(), QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml")))
                .orElseThrow();
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> instances.findById(id).orElseThrow().getState() == QuoteState.CLOSED);
        return id;
    }

    private void modelCall(UUID instanceId, int promptTokens) {
        jdbc.sql("""
                        insert into llm_call (id, instance_id, model, prompt_version, prompt_tokens, completion_tokens,
                                              cost_eur, latency_ms, request, response, created_at)
                        values (:id, :instance, 'stub-model', 'understand-v1', :tokens, 20, 0.0015, 120, :request, 'ok', :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("instance", instanceId)
                .param("tokens", promptTokens)
                .param("request", SECRET_PROMPT)
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    private long statementsFor(UUID id) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/v1/instances/{id}/timeline", id).header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    void theTimelineHasEveryStepInTimeOrderWithCostButNoText() throws Exception {
        UUID id = closedInstance();
        modelCall(id, 300);
        modelCall(id, 200);

        MvcResult result = mvc.perform(
                        get("/api/v1/instances/{id}/timeline", id).header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.promptTokens").value(500))
                .andExpect(jsonPath("$.costEur").value(0.003))
                .andExpect(jsonPath("$.events[?(@.type == 'STEP')].title")
                        .value(org.hamcrest.Matchers.contains(
                                "RECEIVED #1",
                                "UNDERSTOOD #1",
                                "ENRICHED #1",
                                "PRICED #1",
                                "APPROVED #1",
                                "RESPONDED #1")))
                .andExpect(jsonPath("$.events[?(@.type == 'LLM_CALL')].details.promptTokens")
                        .value(org.hamcrest.Matchers.contains(300, 200)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET_PROMPT);
    }

    @Test
    void theTimelineCostsTheSameNumberOfQueriesHoweverLongTheHistory() throws Exception {
        UUID shortOne = closedInstance();
        ScriptedSteps.on(QuoteState.ENRICHED, new java.util.function.Function<>() {
            int attempts;

            @Override
            public StepResult apply(QuoteContext ctx) {
                return ++attempts < 3
                        ? new StepResult.Fail("rates down", true)
                        : new StepResult.Next(QuoteState.PRICED, ctx);
            }
        });
        UUID longOne = closedInstance();
        modelCall(longOne, 10);
        modelCall(longOne, 10);
        modelCall(longOne, 10);

        assertThat(statementsFor(longOne)).isEqualTo(statementsFor(shortOne)).isLessThanOrEqualTo(7);
    }

    @Test
    void aModelCallsFullTextIsForOperators() throws Exception {
        UUID id = closedInstance();
        modelCall(id, 10);
        String callId = jdbc.sql("select id from llm_call where instance_id = :id")
                .param("id", id)
                .query(String.class)
                .single();

        mvc.perform(get("/api/v1/instances/{id}/llm-calls/{call}", id, callId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request").value(SECRET_PROMPT));
        mvc.perform(get("/api/v1/instances/{id}/llm-calls/{call}", id, callId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(IVAN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/instances/{id}/llm-calls/{call}", UUID.randomUUID(), callId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void instancesAreListedNewestFirstAndFilteredByState() throws Exception {
        UUID older = closedInstance();
        UUID newer = closedInstance();

        mvc.perform(get("/api/v1/instances?state=CLOSED&size=2").header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.items[1].id").value(older.toString()))
                .andExpect(jsonPath("$.items[0].customer").value("anna.kowalska@polmarket.test"))
                .andExpect(jsonPath("$.size").value(2));
        mvc.perform(get("/api/v1/instances?state=AWAIT_APPROVAL").header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(newer)).isEmpty());
        mvc.perform(get("/api/v1/instances/{id}", newer).header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Quote request: 12 pallets Warsaw -> Berlin"))
                .andExpect(jsonPath("$.body").doesNotExist());
        mvc.perform(get("/api/v1/instances/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSummaryCountsEveryStateAndTheOpenApprovals() throws Exception {
        closedInstance();
        long closed = jdbc.sql("select count(*) from workflow_instance where state = 'CLOSED'")
                .query(Long.class)
                .single();
        long total = jdbc.sql("select count(*) from workflow_instance")
                .query(Long.class)
                .single();
        long open = jdbc.sql("select count(*) from approval_task where status in ('OPEN', 'ESCALATED')")
                .query(Long.class)
                .single();

        mvc.perform(get("/api/v1/instances/summary").header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.byState.CLOSED").value(closed))
                .andExpect(jsonPath("$.byState.EXCEPTION").isNumber())
                .andExpect(jsonPath("$.byState.length()").value(QuoteState.values().length))
                .andExpect(jsonPath("$.openApprovals").value(open));
        mvc.perform(get("/api/v1/instances/summary").header(HttpHeaders.AUTHORIZATION, bearer(EVE)))
                .andExpect(status().isForbidden());
    }
}
