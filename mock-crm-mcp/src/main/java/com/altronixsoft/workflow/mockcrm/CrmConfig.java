package com.altronixsoft.workflow.mockcrm;

import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import java.time.Clock;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CrmProperties.class)
class CrmConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** No default for the secret: a CRM that accepts everyone's tokens would defeat the point. */
    @Bean
    ApprovalTokens approvalTokens(@Value("${approval.token.secret}") String secret, Clock clock) {
        return new ApprovalTokens(secret, clock);
    }

    @Bean
    ToolCallbackProvider crmToolCallbacks(CrmTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
