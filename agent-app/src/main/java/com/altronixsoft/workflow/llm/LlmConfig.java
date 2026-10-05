package com.altronixsoft.workflow.llm;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GuardProperties.class, LlmPricing.class})
class LlmConfig {

    @Bean
    AuditAdvisor auditAdvisor(LlmCallRepository calls, Clock clock, CostCalculator costs, MeterRegistry meters) {
        return new AuditAdvisor(calls, clock, costs, meters);
    }

    /**
     * The only ChatClient: every model call in the application passes through the audit advisor. Built from Spring
     * AI's builder so its calls are observed (a span inside the step's span).
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, AuditAdvisor audit) {
        return builder.defaultAdvisors(audit).build();
    }
}
