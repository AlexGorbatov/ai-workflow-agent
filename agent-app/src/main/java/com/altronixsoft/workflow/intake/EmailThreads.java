package com.altronixsoft.workflow.intake;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Which instance a mail belongs to. Recording a mail twice is harmless. */
@Service
public class EmailThreads {

    private final EmailThreadRepository threads;
    private final Clock clock;

    EmailThreads(EmailThreadRepository threads, Clock clock) {
        this.threads = threads;
        this.clock = clock;
    }

    public Optional<UUID> findInstance(String messageId) {
        return messageId == null
                ? Optional.empty()
                : threads.findById(messageId).map(EmailThread::getInstanceId);
    }

    public boolean known(String messageId) {
        return threads.existsById(messageId);
    }

    public void saveIn(String messageId, UUID instanceId) {
        save(messageId, instanceId, MailDirection.IN);
    }

    public void saveOut(String messageId, UUID instanceId) {
        save(messageId, instanceId, MailDirection.OUT);
    }

    private void save(String messageId, UUID instanceId, MailDirection direction) {
        if (!threads.existsById(messageId)) {
            threads.save(new EmailThread(messageId, instanceId, direction, clock.instant()));
        }
    }
}
