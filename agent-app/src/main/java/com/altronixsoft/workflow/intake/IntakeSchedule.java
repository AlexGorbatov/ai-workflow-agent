package com.altronixsoft.workflow.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the poller on a timer. Tests switch it off ({@code workflow.intake.poll-enabled=false}) and call the poller directly. */
@Component
@ConditionalOnProperty(name = "workflow.intake.poll-enabled", havingValue = "true", matchIfMissing = true)
class IntakeSchedule {

    private static final Logger log = LoggerFactory.getLogger(IntakeSchedule.class);

    private final EmailPoller poller;

    IntakeSchedule(EmailPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${workflow.intake.poll-interval:5s}")
    void poll() {
        try {
            poller.pollOnce();
        } catch (RuntimeException e) {
            log.warn("Inbox poll failed: {}", e.getMessage());
        }
    }
}
