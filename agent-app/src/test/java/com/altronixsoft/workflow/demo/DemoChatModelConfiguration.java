package com.altronixsoft.workflow.demo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** The chat model of {@code scripts/demo.sh} when no LM Studio is asked for: {@link DemoChatModel}. */
@TestConfiguration(proxyBeanMethods = false)
public class DemoChatModelConfiguration {

    @Bean
    DemoChatModel chatModel(JsonMapper json) {
        return new DemoChatModel(json);
    }
}
