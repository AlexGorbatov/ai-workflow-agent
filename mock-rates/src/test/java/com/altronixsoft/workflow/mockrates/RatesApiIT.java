package com.altronixsoft.workflow.mockrates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rates.simulate.slow-delay=400ms")
@AutoConfigureMockMvc
class RatesApiIT {

    @Autowired
    MockMvc mvc;

    @Test
    void returnsTheCarriersForALane() throws Exception {
        mvc.perform(get("/rates")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "7200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].carrier").isString())
                .andExpect(jsonPath("$[0].cost").isNumber())
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].transitDays").isNumber());
    }

    @Test
    void anUnknownLaneIsNotFound() throws Exception {
        mvc.perform(get("/rates")
                        .param("origin", "Warszawa")
                        .param("destination", "Atlantis")
                        .param("weightKg", "100"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingOrNonsensicalWeightIsABadRequest() throws Exception {
        mvc.perform(get("/rates").param("origin", "Warszawa").param("destination", "Berlin"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/rates")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/rates")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/rates").param("destination", "Berlin").param("weightKg", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulateErrorAnswersServiceUnavailable() throws Exception {
        mvc.perform(get("/rates")
                        .header("X-Simulate", "error")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "7200"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void simulateSlowDelaysTheAnswerButStillAnswers() throws Exception {
        long started = System.nanoTime();

        mvc.perform(get("/rates")
                        .header("X-Simulate", "slow")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "7200"))
                .andExpect(status().isOk());

        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(400);
    }

    @Test
    void anUnknownSimulationModeIsRefusedRatherThanIgnored() throws Exception {
        mvc.perform(get("/rates")
                        .header("X-Simulate", "explode")
                        .param("origin", "Warszawa")
                        .param("destination", "Berlin")
                        .param("weightKg", "7200"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutTheHeaderTheAnswerIsImmediate() throws Exception {
        long started = System.nanoTime();

        mvc.perform(get("/rates")
                        .param("origin", "Milano")
                        .param("destination", "Lyon")
                        .param("weightKg", "4800"))
                .andExpect(status().isOk());

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(400);
    }
}
