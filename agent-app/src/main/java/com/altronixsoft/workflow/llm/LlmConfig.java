package com.altronixsoft.workflow.llm;

import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardProperties.class)
class LlmConfig {

    @Bean
    AuditAdvisor auditAdvisor(LlmCallRepository calls, Clock clock) {
        return new AuditAdvisor(calls, clock);
    }

    /** The only ChatClient: every model call in the application passes through the audit advisor. */
    @Bean
    ChatClient chatClient(ChatModel model, AuditAdvisor audit) {
        return ChatClient.builder(model).defaultAdvisors(audit).build();
    }
}
