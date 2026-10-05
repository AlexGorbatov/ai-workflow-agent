package com.altronixsoft.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.intake.EmailThreads;
import com.altronixsoft.workflow.tools.MailGateway;
import com.altronixsoft.workflow.tools.OutboundMail;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class OutboxRelayFailureIT {

    @MockitoBean
    MailGateway gateway;

    @Autowired
    OutboxService service;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    EmailThreads threads;

    @Test
    void aMailThatCannotBeSentIsMarkedFailedWithTheReasonAndTheRestOfTheBatchStillGoesOut() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        String badId = service.enqueueEmail(bad, "QUOTE", "bad@customer.test", "Bad", "x", null);
        String goodId = service.enqueueEmail(good, "QUOTE", "good@customer.test", "Good", "y", null);
        doThrow(new IllegalStateException("smtp down"))
                .when(gateway)
                .send(org.mockito.ArgumentMatchers.argThat((OutboundMail m) -> m != null && m.to().startsWith("bad@")));
        doNothing()
                .when(gateway)
                .send(org.mockito.ArgumentMatchers.argThat(
                        (OutboundMail m) -> m != null && m.to().startsWith("good@")));

        relay.dispatchOnce();

        OutboxEntry failed = outbox.findByDedupeKey(bad + ":QUOTE").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("smtp down");
        assertThat(threads.findInstance(badId)).isEmpty();
        assertThat(outbox.findByDedupeKey(good + ":QUOTE").orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
        assertThat(threads.findInstance(goodId)).contains(good);
    }

    @Test
    void aFailedRowIsNotRetriedYet() {
        UUID id = UUID.randomUUID();
        service.enqueueEmail(id, "QUOTE", "retry@customer.test", "Q", "x", null);
        doThrow(new IllegalStateException("smtp down")).when(gateway).send(any());
        relay.dispatchOnce();

        relay.dispatchOnce();

        assertThat(outbox.findByDedupeKey(id + ":QUOTE").orElseThrow().getAttempts())
                .isEqualTo(1);
    }
}
