package com.altronixsoft.workflow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Chat model for tests and {@code spring-boot:test-run}: never touches the network, answers from rules
 * ("if the prompt contains X, reply Y"), falls back to a default reply, reports token usage, and records
 * every prompt so tests can assert what was sent.
 */
public class StubChatModel implements ChatModel {

    public static final String DEFAULT_REPLY = "{\"stub\":true}";
    public static final String MODEL_NAME = "stub-model";

    private record Rule(String keyword, String reply) {}

    /** Returned by {@link #whenPromptContains}; completes the rule. */
    public final class PendingRule {

        private final String keyword;

        private PendingRule(String keyword) {
            this.keyword = keyword;
        }

        public StubChatModel replyWith(String text) {
            rules.add(new Rule(keyword, text));
            return StubChatModel.this;
        }
    }

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private volatile String reply = DEFAULT_REPLY;
    private volatile RuntimeException failure;

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        RuntimeException toThrow = failure;
        if (toThrow != null) {
            throw toThrow;
        }
        String promptText = prompt.getContents();
        String answer = rules.stream()
                .filter(rule -> promptText.contains(rule.keyword()))
                .map(Rule::reply)
                .findFirst()
                .orElse(reply);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(MODEL_NAME)
                .usage(new DefaultUsage(tokens(promptText), tokens(answer)))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    }

    /** A rough count (four characters a token); only the audit tests care that it is not zero. */
    private static int tokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /** The first rule whose keyword occurs in the prompt wins; rules are checked in the order added. */
    public PendingRule whenPromptContains(String keyword) {
        return new PendingRule(keyword);
    }

    /** The reply when no rule matches. */
    public void replyWith(String text) {
        this.reply = text;
    }

    /** Every call fails with this until {@link #reset()}. */
    public void failWith(RuntimeException error) {
        this.failure = error;
    }

    public void reset() {
        failure = null;
        prompts.clear();
        rules.clear();
        reply = DEFAULT_REPLY;
    }
}
