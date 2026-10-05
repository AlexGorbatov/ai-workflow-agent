/**
 * The quote policy: typed rules from {@code policy/rules.yml}, {@code PolicyEngine} (flags and numbers in,
 * auto or not plus reasons out) and the Policy step, which issues the approval token for an automatic approval.
 *
 * <p>Never depends on the {@code llm} or {@code intake} packages, Spring AI, or the email text (ArchUnit).
 */
package com.altronixsoft.workflow.policy;
