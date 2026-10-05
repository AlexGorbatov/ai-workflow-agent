package com.altronixsoft.workflow;

import static com.altronixsoft.workflow.TestJwt.OLENA;
import static com.altronixsoft.workflow.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altronixsoft.workflow.audit.WorkflowMetrics;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.ReplyDraft;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * One real request (sample 01) through every step, then: the spans nest (model and tool inside the step), the
 * metrics carry no unbounded tag, and /actuator/prometheus is protected and shows the business metrics.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "workflow.real-steps=true")
@Import({MockApps.class, TestJwt.class, ObservabilityIT.Recorder.class})
class ObservabilityIT {

    /** Remembers every observation with its parent's name, as a span exporter would see the tree. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Recorder {

        record Seen(String name, String parent, String instanceId) {}

        static final List<Seen> seen = new CopyOnWriteArrayList<>();

        @Bean
        ObservationHandler<Observation.Context> recordingHandler() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    Observation.ContextView parent = context.getParentObservation() == null
                            ? null
                            : context.getParentObservation().getContextView();
                    var instance = context.getHighCardinalityKeyValue("instanceId");
                    seen.add(new Seen(
                            context.getName(),
                            parent == null ? null : parent.getName(),
                            instance == null ? null : instance.getValue()));
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    private static final Set<String> FORBIDDEN_TAG_KEYS =
            Set.of("instanceId", "instance_id", "instance", "email", "from", "messageId", "customer", "customerId");
    private static final Pattern UUID_LIKE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowInstanceRepository instances;

    @Autowired
    StubChatModel model;

    @Autowired
    JsonMapper json;

    @Autowired
    MeterRegistry meters;

    @Autowired
    WorkflowMetrics workflowMetrics;

    @Autowired
    MockMvc mvc;

    UUID instanceId;

    @BeforeAll
    void processOneRequest() {
        model.reset();
        model.whenPromptContains("12 pallets Warsaw -> Berlin")
                .replyWith(json.writeValueAsString(new Extraction(
                        Intent.QUOTE_REQUEST,
                        "Warszawa",
                        "Berlin",
                        new BigDecimal("7200"),
                        12,
                        "household goods",
                        LocalDate.of(2026, 10, 6),
                        "en",
                        List.of(),
                        0.95,
                        false)));
        model.whenPromptContains("You write the reply")
                .replyWith(json.writeValueAsString(new ReplyDraft("Your quote", "Our price is 590.00 EUR.")));
        instanceId = engine.start(
                        "obs-" + UUID.randomUUID(), QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml")))
                .orElseThrow();
        await().atMost(20, TimeUnit.SECONDS)
                .until(() -> instances.findById(instanceId).orElseThrow().getState() == QuoteState.FOLLOW_UP);
        workflowMetrics.refresh();
    }

    @Test
    void theModelAndTheToolCallsAreSpansInsideTheirStep() {
        List<Recorder.Seen> ours = Recorder.seen.stream()
                .filter(s -> s.name().equals("workflow.step")
                        && instanceId.toString().equals(s.instanceId()))
                .toList();
        assertThat(ours).as("one span per step").hasSizeGreaterThanOrEqualTo(6);
        assertThat(Recorder.seen)
                .anySatisfy(s -> {
                    assertThat(s.name()).isEqualTo("workflow.tool");
                    assertThat(s.parent()).isEqualTo("workflow.step");
                    assertThat(s.instanceId()).isEqualTo(instanceId.toString());
                })
                .anySatisfy(s -> {
                    assertThat(s.name()).startsWith("spring.ai.chat.client");
                    assertThat(s.parent()).isEqualTo("workflow.step");
                });
    }

    @Test
    void noMetricCarriesAnUnboundedTag() {
        assertThat(meters.getMeters()).isNotEmpty().allSatisfy(meter -> {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(FORBIDDEN_TAG_KEYS)
                        .as("tag key of %s", meter.getId().getName())
                        .doesNotContain(tag.getKey());
                assertThat(tag.getValue())
                        .as("tag %s of %s", tag.getKey(), meter.getId().getName())
                        .doesNotContain("@")
                        .doesNotMatch(".*" + UUID_LIKE.pattern() + ".*");
            }
        });
    }

    @Test
    void theBusinessMetricsAreRegistered() {
        List<String> names = meters.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .distinct()
                .toList();
        assertThat(names)
                .contains(
                        "workflow.instances",
                        "workflow.step.duration",
                        "llm.tokens",
                        "llm.cost.eur",
                        "approvals.open",
                        "outbox.failed",
                        "response.drafts");
        assertThat(meters.get("workflow.instances")
                        .tag("state", "FOLLOW_UP")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("workflow.step.duration")
                        .tag("step", "PRICED")
                        .timer()
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("llm.tokens")
                        .tag("model", StubChatModel.MODEL_NAME)
                        .tag("type", "input")
                        .counter()
                        .count())
                .isPositive();
        // the stub model has no price: its cost is 0, but the metric is there
        assertThat(meters.get("llm.cost.eur")
                        .tag("model", StubChatModel.MODEL_NAME)
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void prometheusIsBehindTheTokenAndShowsTheMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, bearer(OLENA)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .contains(
                        "workflow_instances{",
                        "workflow_step_duration_seconds_bucket{",
                        "workflow_tool_seconds_bucket{",
                        "llm_tokens_total{")
                .doesNotContain(instanceId.toString());
    }
}
