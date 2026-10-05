/**
 * The only package that talks to a language model: steps Understand (structured extraction),
 * Respond (reply drafting with the numeric guard) and Investigator (advisory diagnosis), and the approval
 * briefing.
 *
 * <p>Uses {@code ChatClient} only and audits every call. The model gets no tools, except the Investigator: it
 * gets the read-only tools of its state from {@code ToolGateway}, within a call budget, and never the email
 * text.
 */
package com.altronixsoft.workflow.llm;
