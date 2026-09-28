/**
 * The only package that talks to a language model: steps Understand (structured extraction),
 * Respond (reply drafting with the numeric guard) and Investigator (advisory diagnosis).
 *
 * <p>Uses {@code ChatClient} only, gives the model no tools, and audits every call. Arrives in M2,
 * extended in M4 and M6 — see docs/milestones/M2.md.
 */
package com.altronixsoft.workflow.llm;
