package com.altronixsoft.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class ApplicationSmokeIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ChatModel chatModel;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void everythingElseRequiresAToken() throws Exception {
        mvc.perform(get("/api/v1/instances")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void testsRunAgainstTheStubModelOnly() {
        assertThat(chatModel).isInstanceOf(StubChatModel.class);
    }
}
