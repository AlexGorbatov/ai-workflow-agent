package com.altronixsoft.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.intake.EmailThreads;
import com.altronixsoft.workflow.tools.MailGateway;
import com.altronixsoft.workflow.tools.OutboundMail;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Send failures with a mocked gateway: backoff between attempts, FAILED and the metric after the last. */
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

    @Autowired
    MeterRegistry meters;

    @Test
    void aMailThatCannotBeSentWaitsForItsNextAttemptAndTheRestOfTheBatchStillGoesOut() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        String badId = service.enqueueEmail(bad, "QUOTE", "bad@customer.test", "Bad", "x", null);
        String goodId = service.enqueueEmail(good, "QUOTE", "good@customer.test", "Good", "y", null);
        doThrow(new IllegalStateException("smtp down"))
                .when(gateway)
                .send(argThat((OutboundMail m) -> m != null && m.to().startsWith("bad@")));
        doNothing().when(gateway).send(argThat((OutboundMail m) -> m != null && m.to().startsWith("good@")));
        Instant before = Instant.now();

        relay.dispatchOnce();

        OutboxEntry failed = outbox.findByDedupeKey(bad + ":QUOTE").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("smtp down");
        assertThat(failed.getNextAttemptAt()).isAfter(before);
        assertThat(threads.findInstance(badId)).isEmpty();
        assertThat(outbox.findByDedupeKey(good + ":QUOTE").orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
        assertThat(threads.findInstance(goodId)).contains(good);
    }

    @Test
    void eachFailureWaitsTwiceAsLongAndTheFifthGivesUpAndIsCounted() {
        UUID id = UUID.randomUUID();
        service.enqueueEmail(id, "QUOTE", "down@customer.test", "Q", "x", null);
        doThrow(new IllegalStateException("smtp down"))
                .when(gateway)
                .send(argThat((OutboundMail m) -> m != null && m.to().startsWith("down@")));
        double failedBefore = meters.counter("outbox.failed").count();

        long[] waits = new long[4];
        for (int attempt = 1; attempt <= 5; attempt++) {
            int expected = attempt;
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                relay.dispatchOnce();
                return entry(id).getAttempts() == expected;
            });
            if (attempt < 5) {
                OutboxEntry e = entry(id);
                waits[attempt - 1] = e.getNextAttemptAt().toEpochMilli();
            }
        }

        OutboxEntry gaveUp = entry(id);
        assertThat(gaveUp.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(gaveUp.getAttempts()).isEqualTo(5);
        assertThat(meters.counter("outbox.failed").count()).isEqualTo(failedBefore + 1);
        verify(gateway, times(5)).send(argThat((OutboundMail m) -> m != null && m.to().startsWith("down@")));
        // a FAILED row is never tried again
        relay.dispatchOnce();
        verify(gateway, times(5)).send(argThat((OutboundMail m) -> m != null && m.to().startsWith("down@")));
        assertThat(waits).isSorted();
    }

    private OutboxEntry entry(UUID id) {
        return outbox.findByDedupeKey(id + ":QUOTE").orElseThrow();
    }
}
