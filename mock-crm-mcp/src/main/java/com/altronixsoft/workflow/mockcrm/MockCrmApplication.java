package com.altronixsoft.workflow.mockcrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * In-memory CRM test double exposed as an MCP server (Streamable HTTP, {@code /mcp}): find customers, read credit
 * status, and create an opportunity (the one write, which needs an approval token and an idempotency key).
 */
@SpringBootApplication
public class MockCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockCrmApplication.class, args);
    }
}
