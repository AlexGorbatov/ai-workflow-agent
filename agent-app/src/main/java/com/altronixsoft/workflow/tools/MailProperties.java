package com.altronixsoft.workflow.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param from the address the agent writes from
 * @param mailpitUrl REST API of the mailbox the agent reads (Mailpit)
 * @param inbox the address customers write to; only mail sent to it is read
 */
@ConfigurationProperties("workflow.mail")
public record MailProperties(String from, String mailpitUrl, String inbox) {

    public MailProperties {
        from = from == null ? "quotes@nordline.test" : from;
        mailpitUrl = mailpitUrl == null ? "http://localhost:8025" : mailpitUrl;
        inbox = inbox == null ? "quotes@nordline.test" : inbox;
    }
}
