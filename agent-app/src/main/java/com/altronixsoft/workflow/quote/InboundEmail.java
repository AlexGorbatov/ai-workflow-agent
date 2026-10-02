package com.altronixsoft.workflow.quote;

import java.time.Instant;

public record InboundEmail(
        String messageId, String inReplyTo, String from, String subject, String body, Instant receivedAt) {}
