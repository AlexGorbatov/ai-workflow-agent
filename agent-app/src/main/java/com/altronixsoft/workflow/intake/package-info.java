/**
 * Intake: reads inbound email (Mailpit API or {@code POST /api/v1/intake/emails}), parses MIME,
 * deduplicates by {@code Message-ID} and matches replies to instances by thread headers.
 *
 * <p>Email text is untrusted data: intake stores it and starts an instance, it never interprets it.
 * Arrives in M1 — see docs/milestones/M1.md.
 */
package com.altronixsoft.workflow.intake;
