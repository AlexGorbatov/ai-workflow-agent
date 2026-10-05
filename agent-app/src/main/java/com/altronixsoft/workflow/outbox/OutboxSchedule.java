package com.altronixsoft.workflow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the relay on a timer. Tests switch it off ({@code workflow.outbox.relay-enabled=false}) and call the relay directly. */
@Component
@ConditionalOnProperty(name = "workflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxSchedule {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchedule.class);

    private final OutboxRelay relay;

    OutboxSchedule(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${workflow.outbox.relay-interval:2s}")
    void dispatch() {
        try {
            relay.dispatchOnce();
        } catch (RuntimeException e) {
            log.warn("Outbox dispatch failed: {}", e.getMessage());
        }
    }
}
