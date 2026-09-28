package com.altronixsoft.workflow.mockrates;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Freight lane rates test double over REST. Lanes, fuel surcharge and the chaos mode for failure demos
 * arrive in M3 and M6 — see docs/GUIDE_RU.md §7.3.
 */
@SpringBootApplication
public class MockRatesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockRatesApplication.class, args);
    }
}
