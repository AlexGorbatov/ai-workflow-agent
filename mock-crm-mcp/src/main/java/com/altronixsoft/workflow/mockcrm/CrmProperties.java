package com.altronixsoft.workflow.mockcrm;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The customers the CRM knows, from application.yml. */
@ConfigurationProperties("crm")
public record CrmProperties(List<CustomerRecord> customers) {

    public CrmProperties {
        customers = customers == null ? List.of() : List.copyOf(customers);
    }

    /**
     * @param contacts email addresses of known people at the customer
     * @param domain the company's mail domain; an unknown sender on it matches every customer that has it
     * @param creditStatus OK, WATCH or BLOCKED
     * @param overdueAmount what the customer owes past due; above zero the agent treats the account as on hold
     */
    public record CustomerRecord(
            String id,
            String name,
            String domain,
            List<String> contacts,
            String tier,
            String creditStatus,
            BigDecimal openBalance,
            BigDecimal overdueAmount) {

        public CustomerRecord {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
            openBalance = openBalance == null ? BigDecimal.ZERO : openBalance;
            overdueAmount = overdueAmount == null ? BigDecimal.ZERO : overdueAmount;
            creditStatus = creditStatus == null ? "OK" : creditStatus;
        }
    }
}
