package com.altronixsoft.workflow.mockrates;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Freight lane rates test double over REST: {@code GET /rates}, with a chaos header ({@code X-Simulate}) for
 * failure demos. Lanes and carriers are configuration (application.yml).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockRatesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockRatesApplication.class, args);
    }
}
