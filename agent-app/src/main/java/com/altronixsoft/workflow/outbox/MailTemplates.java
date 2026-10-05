package com.altronixsoft.workflow.outbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Mails written without a model: {@code templates/<name>_<lang>.txt}, first line {@code Subject: ...}, the rest
 * the body, {@code {placeholder}} replaced by value. A language without a template falls back to English.
 */
@Component
public class MailTemplates {

    public static final Set<String> LANGUAGES = Set.of("en", "de", "pl", "ru", "uk");

    public record Mail(String subject, String body) {}

    public Mail render(String name, String language, Map<String, String> values) {
        String lang = language != null && LANGUAGES.contains(language) ? language : "en";
        String raw = read("templates/" + name + "_" + lang + ".txt");
        for (Map.Entry<String, String> e : values.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        }
        // an empty name leaves "Hello ," or "Здравствуйте, !" behind
        raw = raw.replaceAll("[ ,]+([,!])", "$1");
        int newline = raw.indexOf('\n');
        String first = raw.substring(0, newline);
        if (!first.startsWith("Subject: ")) {
            throw new IllegalStateException("Template " + name + "_" + lang + " must start with 'Subject: '");
        }
        return new Mail(
                first.substring("Subject: ".length()).strip(),
                raw.substring(newline + 1).strip() + "\n");
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read template " + path, e);
        }
    }
}
