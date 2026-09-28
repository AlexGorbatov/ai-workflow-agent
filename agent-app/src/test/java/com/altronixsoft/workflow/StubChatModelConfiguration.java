package com.altronixsoft.workflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The test profile creates no chat model ({@code spring.ai.model.chat=none}); this stub takes its
 * place. Unconditional on purpose: the guarantee that tests never reach a paid API is structural, not
 * a matter of property precedence.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubChatModelConfiguration {

    @Bean
    StubChatModel chatModel() {
        return new StubChatModel();
    }
}
