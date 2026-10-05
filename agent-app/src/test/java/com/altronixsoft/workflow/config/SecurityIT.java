package com.altronixsoft.workflow.config;

import static com.altronixsoft.workflow.TestJwt.EVE;
import static com.altronixsoft.workflow.TestJwt.IVAN;
import static com.altronixsoft.workflow.TestJwt.MAX;
import static com.altronixsoft.workflow.TestJwt.OLENA;
import static com.altronixsoft.workflow.TestJwt.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.TestJwt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** Who may call what, through the real filter chain and the Keycloak role mapping. */
@IntegrationTest
@AutoConfigureMockMvc
@Import(TestJwt.class)
class SecurityIT {

    private static final String UNKNOWN = UUID.randomUUID().toString();

    @Autowired
    MockMvc mvc;

    static Stream<Arguments> access() {
        return Stream.of(
                // path, user (null = no token), expected status
                Arguments.of("/api/v1/instances", null, 401),
                Arguments.of("/api/v1/instances", EVE, 403),
                Arguments.of("/api/v1/instances", OLENA, 200),
                Arguments.of("/api/v1/instances", IVAN, 200),
                Arguments.of("/api/v1/instances", MAX, 200),
                Arguments.of("/api/v1/approvals", null, 401),
                Arguments.of("/api/v1/approvals", EVE, 403),
                Arguments.of("/api/v1/approvals", OLENA, 403),
                Arguments.of("/api/v1/approvals", IVAN, 200),
                Arguments.of("/api/v1/approvals", MAX, 200),
                // a model call's full text: operators only (404 = allowed through, nothing there)
                Arguments.of("/api/v1/instances/" + UNKNOWN + "/llm-calls/" + UNKNOWN, IVAN, 403),
                Arguments.of("/api/v1/instances/" + UNKNOWN + "/llm-calls/" + UNKNOWN, OLENA, 404),
                Arguments.of("/actuator/info", null, 401),
                Arguments.of("/actuator/info", EVE, 200),
                Arguments.of("/actuator/health", null, 200),
                Arguments.of("/ui/config.json", null, 200));
    }

    @ParameterizedTest(name = "{0} as {1} → {2}")
    @MethodSource("access")
    void rolesDecideAccess(String path, String user, int expected) throws Exception {
        var request = get(path);
        if (user != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        }
        mvc.perform(request).andExpect(status().is(expected));
    }

    @Test
    void publicPagesAnswerHeadAsWellAsGet() throws Exception {
        mvc.perform(head("/ui/config.json")).andExpect(status().isOk());
        mvc.perform(head("/api/v1/instances")).andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/instances").header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andExpect(status().isUnauthorized());
    }
}
