package com.altronixsoft.workflow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Chat model for tests and {@code spring-boot:test-run}: never touches the network, replies with a
 * fixed JSON document unless told otherwise, and records every prompt so tests can assert what was sent.
 */
public class StubChatModel implements ChatModel {

    public static final String DEFAULT_REPLY = "{\"stub\":true}";

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile String reply = DEFAULT_REPLY;

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public void replyWith(String text) {
        this.reply = text;
    }

    public void reset() {
        prompts.clear();
        reply = DEFAULT_REPLY;
    }
}
