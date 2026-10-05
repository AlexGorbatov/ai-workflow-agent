package com.altronixsoft.workflow.outbox;

import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way a step asks for something to leave the system. Enqueueing is idempotent per dedupe key, so
 * a step that runs again queues nothing new.
 */
@Service
public class OutboxService {

    static final String EMAIL = "EMAIL";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    OutboxService(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Queues a mail for the instance. The key is {@code instanceId:kind}; the Message-ID derives from it, so the
     * same mail always carries the same id. Returns that id (with angle brackets).
     */
    public String enqueueEmail(UUID instanceId, String kind, String to, String subject, String body, String inReplyTo) {
        String dedupeKey = instanceId + ":" + kind;
        String messageId = "<" + instanceId + "." + kind.toLowerCase() + "@nordline.test>";
        insertIfAbsent(instanceId, dedupeKey, new EmailPayload(to, subject, body, messageId, inReplyTo));
        return messageId;
    }

    private void insertIfAbsent(UUID instanceId, String dedupeKey, EmailPayload payload) {
        jdbc.sql("""
                        insert into outbox (id, instance_id, dedupe_key, type, payload, status, attempts, next_attempt_at, created_at)
                        values (:id, :instance, :key, :type, cast(:payload as jsonb), 'PENDING', 0, :now, :now)
                        on conflict (dedupe_key) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("instance", instanceId)
                .param("key", dedupeKey)
                .param("type", EMAIL)
                .param("payload", json.writeValueAsString(payload))
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
    }
}
