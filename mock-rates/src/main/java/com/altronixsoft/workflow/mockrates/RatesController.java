package com.altronixsoft.workflow.mockrates;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class RatesController {

    private final RateCalculator calculator;
    private final RatesProperties properties;

    RatesController(RateCalculator calculator, RatesProperties properties) {
        this.calculator = calculator;
        this.properties = properties;
    }

    /**
     * {@code X-Simulate: slow} holds the answer back (default 8 s); {@code X-Simulate: error} answers 503. Both
     * exist so that the agent's timeout and retry behavior can be shown, not just claimed.
     */
    @GetMapping("/rates")
    List<CarrierRate> rates(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam BigDecimal weightKg,
            @RequestHeader(name = "X-Simulate", required = false) String simulate)
            throws InterruptedException {
        if (weightKg.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weightKg must be positive");
        }
        simulate(simulate);
        return calculator
                .rates(origin, destination, weightKg)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No rates for this lane"));
    }

    private void simulate(String mode) throws InterruptedException {
        if (mode == null) {
            return;
        }
        switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "slow" -> Thread.sleep(properties.simulate().slowDelay());
            case "error" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Simulate must be slow or error");
        }
    }
}
