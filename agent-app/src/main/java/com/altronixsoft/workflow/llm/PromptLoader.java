package com.altronixsoft.workflow.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads {@code prompts/<name>.txt}. The first line is {@code # <version>}; it is recorded with every model
 * call, so a changed prompt can be told apart in the audit table and in evals.
 */
@Component
public class PromptLoader {

    public record Prompt(String version, String text) {}

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public Prompt load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private Prompt read(String name) {
        try {
            String raw = new ClassPathResource("prompts/" + name + ".txt").getContentAsString(StandardCharsets.UTF_8);
            int newline = raw.indexOf('\n');
            String first = newline < 0 ? raw : raw.substring(0, newline);
            if (!first.startsWith("# ")) {
                throw new IllegalStateException("Prompt " + name + " must start with '# <version>'");
            }
            String body = newline < 0 ? "" : raw.substring(newline + 1).strip();
            return new Prompt(first.substring(2).strip(), body);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read prompt " + name, e);
        }
    }
}
