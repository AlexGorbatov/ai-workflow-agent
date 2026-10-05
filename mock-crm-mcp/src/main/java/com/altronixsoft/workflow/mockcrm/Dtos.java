package com.altronixsoft.workflow.mockcrm;

import java.math.BigDecimal;
import java.time.Instant;

/** What the tools return. Field names are the contract with the agent. */
final class Dtos {

    private Dtos() {}

    record CustomerDto(String id, String name, String domain, String tier) {}

    record CreditStatusDto(
            String customerId, String status, BigDecimal openBalance, BigDecimal overdueAmount, String currency) {}

    record OpportunityDto(String id, String customerId, BigDecimal amount, String currency, Instant createdAt) {}
}
