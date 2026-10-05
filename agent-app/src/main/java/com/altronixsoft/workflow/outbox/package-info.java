/**
 * Outbox for mail: a step queues a row keyed by {@code instanceId:kind} (a repeated step queues nothing new), and
 * the relay sends due rows with a stable Message-ID, retrying with backoff.
 *
 * <p>At-least-once delivery; a duplicate after a crash carries the same Message-ID.
 */
package com.altronixsoft.workflow.outbox;
