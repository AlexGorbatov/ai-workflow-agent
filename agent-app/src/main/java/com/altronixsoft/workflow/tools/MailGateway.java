package com.altronixsoft.workflow.tools;

/** The way out for mail. Throws if the mail could not be handed over. */
public interface MailGateway {

    void send(OutboundMail mail);
}
