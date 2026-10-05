package com.altronixsoft.workflow;

import com.altronixsoft.workflow.demo.DemoChatModelConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * {@code ./mvnw -pl agent-app spring-boot:test-run}: runs locally with no API keys — test and demo
 * profiles, PostgreSQL from Testcontainers, the stub chat model.
 *
 * <p>{@code DEMO=1} is what {@code scripts/demo.sh} runs: the infrastructure from {@code compose.yaml}
 * instead of Testcontainers, the real steps with the demo profile's fast timers, and {@link
 * com.altronixsoft.workflow.demo.DemoChatModel}, which answers the sample emails without a key.
 *
 * <p>{@code LM_STUDIO=1} swaps the stub (or the demo model) for a local LM Studio model (profile {@code
 * lmstudio}). It is an opt-in per invocation, not a standing profile, because it needs a running LM Studio
 * server.
 */
public class TestWorkflowAgentApplication {

    public static void main(String[] args) {
        boolean lmStudio = "1".equals(System.getenv("LM_STUDIO"));
        boolean demo = "1".equals(System.getenv("DEMO"));

        if (demo) {
            var application = SpringApplication.from(WorkflowAgentApplication::main)
                    .withAdditionalProfiles(lmStudio ? new String[] {"demo", "lmstudio"} : new String[] {"demo"});
            if (!lmStudio) {
                // no OpenAI model and no key: the demo model takes its place
                application = application.with(DemoChatModelConfiguration.class);
                args = withDefaults(args, "--spring.ai.model.chat=none", "--spring.ai.openai.api-key=not-used");
            }
            application.run(args);
            return;
        }

        var application = SpringApplication.from(WorkflowAgentApplication::main)
                .with(ContainersConfig.class)
                .withAdditionalProfiles(
                        lmStudio ? new String[] {"test", "demo", "lmstudio"} : new String[] {"test", "demo"});
        if (!lmStudio) {
            application = application.with(StubChatModelConfiguration.class);
        }
        application.run(args);
    }

    private static String[] withDefaults(String[] args, String... defaults) {
        String[] all = new String[defaults.length + args.length];
        System.arraycopy(defaults, 0, all, 0, defaults.length);
        System.arraycopy(args, 0, all, defaults.length, args.length);
        return all;
    }
}
