package com.altronixsoft.workflow.engine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineProperties.class)
class EngineConfig {

    /**
     * {@code workflow.registry.require-all-states} makes startup fail while a working state has no step.
     * Off until every step exists; engine tests rely on fake steps and leave it off.
     */
    @Bean
    StepRegistry stepRegistry(
            ObjectProvider<Step> steps, @Value("${workflow.registry.require-all-states:false}") boolean requireAll) {
        return new StepRegistry(steps.orderedStream().toList(), requireAll);
    }
}
