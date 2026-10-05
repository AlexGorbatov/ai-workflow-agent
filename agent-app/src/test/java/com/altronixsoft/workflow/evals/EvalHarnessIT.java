package com.altronixsoft.workflow.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.llm.UnderstandStep;
import com.altronixsoft.workflow.policy.PolicyEngine;
import com.altronixsoft.workflow.quote.PricingCalculator;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eval harness itself, in the ordinary build: the stub model answers every case with exactly its labels, so a
 * perfect model is simulated. Every score must then be 1.0, which also proves that the labelled routes agree with
 * the real policy and pricing code. Says nothing about a real model; that is EvalRunnerIT, with -Pevals.
 */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
class EvalHarnessIT {

    @Autowired
    UnderstandStep understand;

    @Autowired
    LlmCallRepository llmCalls;

    @Autowired
    PricingCalculator pricing;

    @Autowired
    PolicyEngine policy;

    @Autowired
    StubChatModel model;

    @Autowired
    JsonMapper json;

    @Test
    void aModelThatAnswersExactlyTheLabelsScoresOneOnEveryMetric() throws Exception {
        List<EvalCase> cases = EvalCases.load(json);
        assertThat(cases).hasSize(40);
        assertThat(cases.stream().map(EvalCase::category).distinct())
                .containsExactlyInAnyOrder("normal", "incomplete", "not-a-request", "injection", "ambiguous");

        EvalRunner runner = new EvalRunner(understand, llmCalls, pricing, policy, json);
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            model.reset();
            model.replyWith(json.writeValueAsString(labels(c)));
            results.add(runner.run(c));
        }
        EvalReport report = EvalReport.of("stub (answers the labels)", results, EvalCases.thresholds());
        Path dir = Path.of("target", "evals-harness");
        report.write(dir, json);

        assertThat(report.failures()).isEmpty();
        assertThat(report.intent()).as(report.markdown()).isEqualTo(1.0);
        assertThat(report.fields()).as(report.markdown()).isEqualTo(1.0);
        assertThat(report.route()).as(report.markdown()).isEqualTo(1.0);
        assertThat(report.injectionRecall()).isEqualTo(1.0);
        assertThat(Files.readString(dir.resolve("report.md"))).contains("| n01-en-gold | normal | ✓ |");
        assertThat(Files.readString(dir.resolve("report.json"))).contains("\"passed\" : true");
    }

    /** The extraction a perfect model would return for the case: its labels, the first spelling of each city. */
    private static Extraction labels(EvalCase c) {
        Map<String, Object> f = c.expect().fields();
        Intent intent = Intent.valueOf(c.expect().intent());
        if (intent != Intent.QUOTE_REQUEST) {
            return new Extraction(
                    intent, null, null, null, null, null, null, (String) f.get("language"), List.of(), 0.95, false);
        }
        boolean dangerous = Boolean.TRUE.equals(f.get("dangerous"));
        return new Extraction(
                intent,
                first(f.get("origin")),
                first(f.get("destination")),
                f.get("weightKg") == null ? null : new BigDecimal(String.valueOf(f.get("weightKg"))),
                f.get("pallets") == null ? null : ((Number) f.get("pallets")).intValue(),
                dangerous ? "ADR goods" : "general cargo",
                f.get("pickupDate") == null ? null : LocalDate.parse(String.valueOf(f.get("pickupDate"))),
                (String) f.get("language"),
                c.expect().missing(),
                0.95,
                c.expect().injection());
    }

    private static String first(Object value) {
        if (value instanceof List<?> list) {
            return String.valueOf(list.getFirst());
        }
        return value == null ? null : String.valueOf(value);
    }
}
