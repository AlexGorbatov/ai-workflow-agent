package com.altronixsoft.workflow.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Links one mail (by Message-ID) to the instance it belongs to. */
@Entity
@Table(name = "email_thread")
public class EmailThread {

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MailDirection direction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmailThread() {}

    EmailThread(String messageId, UUID instanceId, MailDirection direction, Instant createdAt) {
        this.messageId = messageId;
        this.instanceId = instanceId;
        this.direction = direction;
        this.createdAt = createdAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public MailDirection getDirection() {
        return direction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
