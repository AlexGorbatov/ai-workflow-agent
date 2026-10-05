package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApprovalConfig {

    /** No default for the secret: the CRM verifies these tokens, and a guessable key would make them worthless. */
    @Bean
    ApprovalTokens approvalTokens(@Value("${approval.token.secret}") String secret, Clock clock) {
        return new ApprovalTokens(secret, clock);
    }
}
