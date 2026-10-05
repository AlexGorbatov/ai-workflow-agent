package com.altronixsoft.workflow.tools;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends mail over SMTP with the Message-ID it was given, so a resend after a crash carries the same id
 * and the duplicate can be recognized.
 */
@Component
class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final MailProperties properties;

    SmtpMailGateway(JavaMailSender sender, MailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void send(OutboundMail mail) {
        try {
            MimeMessage message =
                    new FixedIdMimeMessage(sender.createMimeMessage().getSession(), mail.messageId());
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.body(), false);
            if (mail.inReplyTo() != null) {
                message.setHeader("In-Reply-To", mail.inReplyTo());
                message.setHeader("References", mail.inReplyTo());
            }
            sender.send(message);
        } catch (MessagingException | MailException e) {
            throw new IllegalStateException("Could not send mail " + mail.messageId() + ": " + e.getMessage(), e);
        }
    }

    /** A MimeMessage whose Message-ID is ours; the default one is regenerated on every save. */
    private static final class FixedIdMimeMessage extends MimeMessage {

        private final String messageId;

        FixedIdMimeMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }
}
