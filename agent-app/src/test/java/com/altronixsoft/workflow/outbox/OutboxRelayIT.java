package com.altronixsoft.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MailpitTestClient;
import com.altronixsoft.workflow.intake.EmailThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;

@IntegrationTest
class OutboxRelayIT {

    @Autowired
    OutboxService service;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    EmailThreads threads;

    @Autowired
    JavaMailSender sender;

    @Value("${workflow.mail.mailpit-url}")
    String mailpitUrl;

    private MailpitTestClient mailpit() {
        return new MailpitTestClient(mailpitUrl, sender);
    }

    private static String customer() {
        return "c-" + UUID.randomUUID() + "@customer.test";
    }

    @Test
    void queueingTheSameMailTwiceKeepsOneRow() {
        UUID instance = UUID.randomUUID();

        String first = service.enqueueEmail(instance, "CLARIFICATION", customer(), "Question", "Body", null);
        String second = service.enqueueEmail(instance, "CLARIFICATION", customer(), "Question", "Body", null);

        assertThat(second).isEqualTo(first).isEqualTo("<" + instance + ".clarification@nordline.test>");
        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(instance)).hasSize(1);
        OutboxEntry row = outbox.findByDedupeKey(instance + ":CLARIFICATION").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getType()).isEqualTo("EMAIL");
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void differentKindsForOneInstanceAreDifferentMails() {
        UUID instance = UUID.randomUUID();

        service.enqueueEmail(instance, "CLARIFICATION", customer(), "A", "a", null);
        service.enqueueEmail(instance, "QUOTE", customer(), "B", "b", null);

        assertThat(outbox.findByInstanceIdOrderByCreatedAtAsc(instance)).hasSize(2);
    }

    @Test
    void aQueuedMailLeavesWithItsOwnMessageIdAndIsLinkedToTheInstance() {
        UUID instance = UUID.randomUUID();
        String to = customer();
        String messageId = service.enqueueEmail(instance, "QUOTE", to, "Your quote", "Price: 1210 EUR", null);

        int sent = relay.dispatchOnce();

        assertThat(sent).isGreaterThanOrEqualTo(1);
        List<MailpitTestClient.Summary> delivered = mailpit().messagesTo(to);
        assertThat(delivered).hasSize(1);
        assertThat("<" + delivered.get(0).messageId() + ">").isEqualTo(messageId);
        assertThat(delivered.get(0).subject()).isEqualTo("Your quote");
        OutboxEntry row = outbox.findByDedupeKey(instance + ":QUOTE").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(threads.findInstance(messageId)).contains(instance);
    }

    @Test
    void aSentRowIsNotSentAgain() {
        UUID instance = UUID.randomUUID();
        String to = customer();
        service.enqueueEmail(instance, "QUOTE", to, "Your quote", "Body", null);
        relay.dispatchOnce();

        relay.dispatchOnce();
        service.enqueueEmail(instance, "QUOTE", to, "Your quote", "Body", null);
        relay.dispatchOnce();

        assertThat(mailpit().messagesTo(to)).hasSize(1);
    }

    @Test
    void twoRelaysRunningTogetherSendEveryMailExactlyOnce() throws Exception {
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String to = customer();
            recipients.add(to);
            service.enqueueEmail(UUID.randomUUID(), "QUOTE", to, "Quote " + i, "Body", null);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = () -> {
                int total = 0;
                for (int round = 0; round < 3; round++) {
                    total += relay.dispatchOnce();
                }
                return total;
            };
            List<Future<Integer>> results = pool.invokeAll(List.of(run, run));
            for (Future<Integer> f : results) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        for (String to : recipients) {
            assertThat(mailpit().messagesTo(to)).as("mails to %s", to).hasSize(1);
        }
    }
}
