/**
 * The only package that talks to a language model: steps Understand (structured extraction),
 * Respond (reply drafting with the numeric guard) and Investigator (advisory diagnosis).
 *
 * <p>Uses {@code ChatClient} only and audits every call. The model gets no tools, except the Investigator: it
 * gets the read-only tools of its state from {@code ToolGateway}, within a call budget, and never the email
 * text. Arrives in M2, extended in M4 and M6 — see docs/milestones/M2.md.
 */
package com.altronixsoft.workflow.llm;
