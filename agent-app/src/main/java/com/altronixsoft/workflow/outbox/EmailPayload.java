package com.altronixsoft.workflow.outbox;

/** What an EMAIL outbox row carries. {@code messageId} and {@code inReplyTo} include the angle brackets. */
public record EmailPayload(String to, String subject, String body, String messageId, String inReplyTo) {}
