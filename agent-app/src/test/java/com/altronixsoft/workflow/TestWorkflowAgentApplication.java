package com.altronixsoft.workflow;

import org.springframework.boot.SpringApplication;

/**
 * {@code ./mvnw -pl agent-app spring-boot:test-run}: runs locally with no API keys — test and demo
 * profiles, PostgreSQL from Testcontainers, the stub chat model.
 *
 * <p>{@code LM_STUDIO=1} swaps the stub for a local LM Studio model (profile {@code lmstudio}). It is an
 * opt-in per invocation, not a standing profile, because it needs a running LM Studio server.
 */
public class TestWorkflowAgentApplication {

    public static void main(String[] args) {
        boolean lmStudio = "1".equals(System.getenv("LM_STUDIO"));

        var application = SpringApplication.from(WorkflowAgentApplication::main)
                .with(ContainersConfig.class)
                .withAdditionalProfiles(
                        lmStudio ? new String[] {"test", "demo", "lmstudio"} : new String[] {"test", "demo"});
        if (!lmStudio) {
            application = application.with(StubChatModelConfiguration.class);
        }
        application.run(args);
    }
}
