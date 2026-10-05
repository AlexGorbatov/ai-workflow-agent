package com.altronixsoft.workflow.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.ContainersConfig;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.llm.UnderstandStep;
import com.altronixsoft.workflow.policy.PolicyEngine;
import com.altronixsoft.workflow.quote.PricingCalculator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eval suite on a real model: ./mvnw -pl agent-app -Pevals verify (OPENAI_API_KEY), or with LM_STUDIO=1 on a
 * local LM Studio model. Never part of the ordinary build (@Tag("eval")). Writes target/evals/report.md and
 * report.json, then fails if a score is below its threshold in evals/thresholds.yaml.
 */
@Tag("eval")
@SpringBootTest
@ActiveProfiles(resolver = EvalRunnerIT.Profiles.class)
@Import(ContainersConfig.class)
@TestPropertySource(
        properties = {
            "workflow.real-steps=true",
            "spring.ai.model.chat=openai",
            "spring.ai.openai.api-key=${OPENAI_API_KEY:not-set}"
        })
class EvalRunnerIT {

    /** The test profile, plus lmstudio when LM_STUDIO=1. */
    static class Profiles implements ActiveProfilesResolver {

        @Override
        public String[] resolve(Class<?> testClass) {
            return localModel() ? new String[] {"test", "lmstudio"} : new String[] {"test"};
        }
    }

    static boolean localModel() {
        return "1".equals(System.getenv("LM_STUDIO"));
    }

    @Autowired
    UnderstandStep understand;

    @Autowired
    LlmCallRepository llmCalls;

    @Autowired
    PricingCalculator pricing;

    @Autowired
    PolicyEngine policy;

    @Autowired
    ChatModel model;

    @Autowired
    JsonMapper json;

    @Test
    void theRealModelMeetsTheThresholds() {
        String key = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(localModel() || (key != null && !key.isBlank()), "OPENAI_API_KEY is not set");

        EvalRunner runner = new EvalRunner(understand, llmCalls, pricing, policy, json);
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase c : EvalCases.load(json)) {
            results.add(runner.run(c));
        }
        EvalReport report =
                EvalReport.of(String.valueOf(model.getDefaultOptions().getModel()), results, EvalCases.thresholds());
        report.write(Path.of("target", "evals"), json);

        assertThat(report.failures()).as(report.markdown()).isEmpty();
    }
}
