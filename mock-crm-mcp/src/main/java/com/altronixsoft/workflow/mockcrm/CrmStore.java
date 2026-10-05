package com.altronixsoft.workflow.mockcrm;

import com.altronixsoft.workflow.mockcrm.CrmProperties.CustomerRecord;
import com.altronixsoft.workflow.mockcrm.Dtos.CreditStatusDto;
import com.altronixsoft.workflow.mockcrm.Dtos.CustomerDto;
import com.altronixsoft.workflow.mockcrm.Dtos.OpportunityDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** The CRM's data, in memory. Opportunities are keyed by the caller's idempotency key. */
@Component
class CrmStore {

    private final List<CustomerRecord> customers;
    private final Clock clock;
    private final Map<String, OpportunityDto> opportunities = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    CrmStore(CrmProperties properties, Clock clock) {
        this.customers = properties.customers();
        this.clock = clock;
    }

    /** The contact's own customer if the address is known, otherwise every customer on the sender's domain. */
    List<CustomerDto> findByEmail(String email) {
        String address = email.strip().toLowerCase(Locale.ROOT);
        List<CustomerRecord> exact = customers.stream()
                .filter(c -> c.contacts().stream().anyMatch(a -> a.equalsIgnoreCase(address)))
                .toList();
        if (!exact.isEmpty()) {
            return exact.stream().map(CrmStore::dto).toList();
        }
        int at = address.lastIndexOf('@');
        if (at < 0) {
            return List.of();
        }
        String domain = address.substring(at + 1);
        return customers.stream()
                .filter(c -> c.domain().equalsIgnoreCase(domain))
                .map(CrmStore::dto)
                .toList();
    }

    Optional<CreditStatusDto> creditStatus(String customerId) {
        return find(customerId)
                .map(c -> new CreditStatusDto(c.id(), c.creditStatus(), c.openBalance(), c.overdueAmount(), "EUR"));
    }

    boolean exists(String customerId) {
        return find(customerId).isPresent();
    }

    /** Creates the opportunity once per key; every later call with that key returns the first result. */
    OpportunityDto createOpportunity(String idempotencyKey, String customerId, BigDecimal amount, String currency) {
        return opportunities.computeIfAbsent(
                idempotencyKey,
                key -> new OpportunityDto(
                        "OPP-%04d".formatted(sequence.incrementAndGet()),
                        customerId,
                        amount,
                        currency,
                        clock.instant()));
    }

    /** For tests: how many opportunities exist for the key (0 or 1). */
    int opportunityCount(String idempotencyKey) {
        return opportunities.containsKey(idempotencyKey) ? 1 : 0;
    }

    private Optional<CustomerRecord> find(String customerId) {
        return customers.stream().filter(c -> c.id().equals(customerId)).findFirst();
    }

    private static CustomerDto dto(CustomerRecord c) {
        return new CustomerDto(c.id(), c.name(), c.domain(), c.tier());
    }
}
