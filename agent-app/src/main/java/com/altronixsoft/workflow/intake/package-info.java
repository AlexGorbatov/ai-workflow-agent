/**
 * Intake: reads inbound email from the Mailpit API, deduplicates by {@code Message-ID} and matches replies to
 * instances by thread headers.
 *
 * <p>Email text is untrusted data: intake stores it and starts an instance, it never interprets it.
 */
package com.altronixsoft.workflow.intake;
