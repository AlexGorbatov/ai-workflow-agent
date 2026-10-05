package com.altronixsoft.workflow.intake;

import com.altronixsoft.workflow.engine.InvalidSignalException;
import com.altronixsoft.workflow.engine.Signal;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.quote.InboundEmail;
import com.altronixsoft.workflow.quote.QuoteContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns inbox mail into workflow activity: a new mail starts an instance, a reply to a mail the instance
 * sent or received signals that instance. A mail is processed once, however often it is seen.
 */
@Service
public class EmailPoller {

    private static final Logger log = LoggerFactory.getLogger(EmailPoller.class);

    private final EmailSource source;
    private final EmailThreads threads;
    private final WorkflowEngine engine;

    EmailPoller(EmailSource source, EmailThreads threads, WorkflowEngine engine) {
        this.source = source;
        this.threads = threads;
        this.engine = engine;
    }

    /** Reads the inbox once; returns how many mails were new. One bad mail does not stop the others. */
    public int pollOnce() {
        int processed = 0;
        for (InboundEmail email : source.fetchNew()) {
            if (threads.known(email.messageId())) {
                continue;
            }
            try {
                process(email);
                processed++;
            } catch (RuntimeException e) {
                log.warn("Could not process mail {}; it will be retried", email.messageId(), e);
            }
        }
        return processed;
    }

    private void process(InboundEmail email) {
        Optional<UUID> parent = threads.findInstance(email.inReplyTo());
        UUID instanceId;
        if (parent.isPresent()) {
            instanceId = parent.get();
            reply(instanceId, email);
        } else {
            instanceId = engine.start(email.messageId(), QuoteContext.of(email))
                    .or(() -> engine.findByBusinessKey(email.messageId()))
                    .orElseThrow();
        }
        threads.saveIn(email.messageId(), instanceId);
    }

    private void reply(UUID instanceId, InboundEmail email) {
        try {
            engine.signal(instanceId, new Signal.CustomerReplied(email.body()));
        } catch (InvalidSignalException notWaiting) {
            // The instance is not waiting for a reply (still running, or already finished).
            log.warn(
                    "Reply {} did not apply to instance {}: {}",
                    email.messageId(),
                    instanceId,
                    notWaiting.getMessage());
        }
    }
}
