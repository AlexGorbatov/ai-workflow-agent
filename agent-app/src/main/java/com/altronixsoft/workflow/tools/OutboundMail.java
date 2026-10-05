package com.altronixsoft.workflow.tools;

/** A mail ready to send. {@code messageId} and {@code inReplyTo} include the angle brackets. */
public record OutboundMail(String to, String subject, String body, String messageId, String inReplyTo) {}
