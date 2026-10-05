package com.altronixsoft.workflow;

import com.altronixsoft.workflow.quote.InboundEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Reads the files in {@code samples/emails} the way a mail client would: decoded subject, text body. */
public final class SampleEmails {

    private SampleEmails() {}

    public static InboundEmail read(String fileName) {
        try (FileInputStream in =
                new FileInputStream(Path.of("..", "samples", "emails", fileName).toFile())) {
            MimeMessage message = new MimeMessage(Session.getDefaultInstance(new Properties()), in);
            return new InboundEmail(
                    message.getHeader("Message-ID", null),
                    message.getHeader("In-Reply-To", null),
                    ((InternetAddress) message.getFrom()[0]).getAddress(),
                    message.getSubject(),
                    ((String) message.getContent()).strip(),
                    message.getSentDate().toInstant());
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("Cannot read sample " + fileName, e);
        }
    }

    public static String text(String fileName) {
        InboundEmail email = read(fileName);
        return "Subject: " + email.subject() + "\n\n" + email.body();
    }
}
