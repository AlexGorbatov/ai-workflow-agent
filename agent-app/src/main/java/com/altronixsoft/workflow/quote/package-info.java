/**
 * The workflow's data ({@code QuoteContext}, {@code QuoteState}) and the deterministic price: {@code BigDecimal}
 * from carrier rates, customer tier margins and surcharges ({@code pricing.*}), with every part kept as a
 * breakdown line.
 *
 * <p>Never depends on the {@code llm} package or Spring AI.
 */
package com.altronixsoft.workflow.quote;
