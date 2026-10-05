package com.altronixsoft.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/** Test-side view of the Mailpit container: send a sample email in, list what is in the mailbox. */
public class MailpitTestClient {

    public record Summary(
            @JsonProperty("ID") String id,
            @JsonProperty("MessageID") String messageId,
            @JsonProperty("Subject") String subject,
            @JsonProperty("To") List<Address> to) {}

    public record Address(@JsonProperty("Address") String address) {}

    private record Messages(@JsonProperty("messages") List<Summary> messages) {}

    private final RestClient http;
    private final JavaMailSender sender;

    public MailpitTestClient(String apiUrl, JavaMailSender sender) {
        this.http = RestClient.builder().baseUrl(apiUrl).build();
        this.sender = sender;
    }

    /**
     * Sends a file from {@code samples/emails} with its own headers and body, but with {@code suffix} woven into
     * the Message-ID, In-Reply-To and References so that tests sharing a database never collide. A reply
     * sent with the same suffix as its parent still points at it. Returns the Message-ID sent.
     */
    public String sendSample(String fileName, String suffix) {
        Path file = Path.of("..", "samples", "emails", fileName);
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            MimeMessage message = new MimeMessage(Session.getDefaultInstance(new Properties()), in);
            for (String header : List.of("Message-ID", "In-Reply-To", "References")) {
                String value = message.getHeader(header, null);
                if (value != null) {
                    message.setHeader(header, value.replace("@", "." + suffix + "@"));
                }
            }
            sender.send(message);
            return message.getHeader("Message-ID", null);
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("Could not send sample " + fileName, e);
        }
    }

    /** Sends a plain mail; {@code inReplyTo} may be null. Returns the Message-ID used. */
    public String sendPlain(String from, String to, String subject, String body, String inReplyTo) {
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(from);
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, to);
            message.setSubject(subject);
            message.setText(body, "UTF-8");
            String id = "<plain." + java.util.UUID.randomUUID() + "@customer.test>";
            message.setHeader("Message-ID", id);
            if (inReplyTo != null) {
                message.setHeader("In-Reply-To", inReplyTo);
            }
            sender.send(message);
            return id;
        } catch (MessagingException e) {
            throw new IllegalStateException("Could not send mail", e);
        }
    }

    public List<Summary> messagesTo(String address) {
        Messages found = http.get()
                .uri("/api/v1/search?query={q}&limit=100", "to:" + address)
                .retrieve()
                .body(Messages.class);
        return found == null || found.messages() == null ? List.of() : found.messages();
    }
}
