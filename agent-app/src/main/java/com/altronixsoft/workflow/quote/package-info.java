/**
 * Deterministic quote calculation in {@code BigDecimal} from lane rates, customer contract and
 * {@code pricing.yml}; every intermediate amount is kept as a quote line.
 *
 * <p>Never depends on the {@code llm} package or Spring AI. Arrives in M3 — see docs/milestones/M3.md.
 */
package com.altronixsoft.workflow.quote;
