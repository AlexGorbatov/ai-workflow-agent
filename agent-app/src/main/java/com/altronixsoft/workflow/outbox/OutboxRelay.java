package com.altronixsoft.workflow.outbox;

import com.altronixsoft.workflow.intake.EmailThreads;
import com.altronixsoft.workflow.tools.MailGateway;
import com.altronixsoft.workflow.tools.OutboundMail;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends what is in the outbox. Each batch is one transaction: the mail goes out, the row is marked sent and
 * the mail is linked to its instance together. If the process dies after the SMTP hand-over but before the
 * commit, the next run sends the same mail again with the same Message-ID (at-least-once).
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 20;

    private final OutboxRepository outbox;
    private final MailGateway mail;
    private final EmailThreads threads;
    private final TransactionTemplate tx;
    private final Clock clock;

    OutboxRelay(OutboxRepository outbox, MailGateway mail, EmailThreads threads, TransactionTemplate tx, Clock clock) {
        this.outbox = outbox;
        this.mail = mail;
        this.threads = threads;
        this.tx = tx;
        this.clock = clock;
    }

    /** Sends one batch of due rows; returns how many went out. A failed row is marked FAILED and does not stop the batch. */
    public int dispatchOnce() {
        Integer sent = tx.execute(status -> {
            List<OutboxEntry> due = outbox.lockDue(clock.instant(), BATCH);
            int count = 0;
            for (OutboxEntry entry : due) {
                if (send(entry)) {
                    count++;
                }
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    private boolean send(OutboxEntry entry) {
        EmailPayload p = entry.getPayload();
        try {
            mail.send(new OutboundMail(p.to(), p.subject(), p.body(), p.messageId(), p.inReplyTo()));
        } catch (RuntimeException e) {
            log.warn("Outbox row {} failed: {}", entry.getId(), e.getMessage());
            entry.markFailed(e.getMessage());
            return false;
        }
        threads.saveOut(p.messageId(), entry.getInstanceId());
        entry.markSent(clock.instant());
        return true;
    }
}
