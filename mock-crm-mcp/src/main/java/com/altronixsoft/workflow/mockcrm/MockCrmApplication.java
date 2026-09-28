package com.altronixsoft.workflow.mockcrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * In-memory CRM test double exposed as an MCP server. Tools (find_customer, get_shipment_history,
 * create_quote, update_quote_status) and fixtures arrive in M3 and M4 — see docs/GUIDE_RU.md §7.2.
 */
@SpringBootApplication
public class MockCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockCrmApplication.class, args);
    }
}
