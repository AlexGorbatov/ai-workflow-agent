package com.altronixsoft.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MailpitTestClient;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** SMTP is down twice, then back: the real Mailpit gets the mail exactly once, with its Message-ID. */
@IntegrationTest
class OutboxRetryIT {

    @MockitoSpyBean
    JavaMailSender sender;

    @Autowired
    OutboxService service;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outbox;

    @Value("${workflow.mail.mailpit-url}")
    String mailpitUrl;

    @Test
    void aMailThatFailedTwiceGoesOutOnceWhenSmtpIsBack() {
        UUID id = UUID.randomUUID();
        String to = "retry-" + id.toString().substring(0, 8) + "@customer.test";
        String messageId = service.enqueueEmail(id, "QUOTE", to, "Your quote", "Price: 590.00 EUR", null);
        doThrow(new MailSendException("connection refused"))
                .doThrow(new MailSendException("connection refused"))
                .doCallRealMethod()
                .when(sender)
                .send(any(MimeMessage.class));

        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            relay.dispatchOnce();
            return outbox.findByDedupeKey(id + ":QUOTE").orElseThrow().getStatus() == OutboxStatus.SENT;
        });

        assertThat(outbox.findByDedupeKey(id + ":QUOTE").orElseThrow().getAttempts())
                .isEqualTo(3);
        relay.dispatchOnce();
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, sender);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !mailpit.messagesTo(to).isEmpty());
        assertThat(mailpit.messagesTo(to))
                .singleElement()
                .satisfies(m -> assertThat("<" + m.messageId() + ">").isEqualTo(messageId));
    }
}
