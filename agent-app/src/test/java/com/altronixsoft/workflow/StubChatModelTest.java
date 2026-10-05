package com.altronixsoft.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

class StubChatModelTest {

    private final StubChatModel stub = new StubChatModel();

    private String ask(String text) {
        return stub.call(new Prompt(text)).getResult().getOutput().getText();
    }

    @Test
    void answersWithTheDefaultWhenNoRuleMatches() {
        assertThat(ask("anything")).isEqualTo(StubChatModel.DEFAULT_REPLY);
    }

    @Test
    void theFirstMatchingRuleWins() {
        stub.whenPromptContains("pallets").replyWith("{\"a\":1}");
        stub.whenPromptContains("Berlin").replyWith("{\"b\":2}");

        assertThat(ask("12 pallets to Berlin")).isEqualTo("{\"a\":1}");
        assertThat(ask("one box to Berlin")).isEqualTo("{\"b\":2}");
        assertThat(ask("one box to Rome")).isEqualTo(StubChatModel.DEFAULT_REPLY);
    }

    @Test
    void theDefaultCanBeReplaced() {
        stub.replyWith("{\"x\":0}");

        assertThat(ask("whatever")).isEqualTo("{\"x\":0}");
    }

    @Test
    void usageAndModelAreReported() {
        var response = stub.call(new Prompt("twelve pallets of goods"));

        assertThat(response.getMetadata().getModel()).isEqualTo(StubChatModel.MODEL_NAME);
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isPositive();
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isPositive();
    }

    @Test
    void resetForgetsRulesPromptsAndTheCustomDefault() {
        stub.whenPromptContains("x").replyWith("rule");
        stub.replyWith("custom");
        ask("x");

        stub.reset();

        assertThat(stub.prompts()).isEmpty();
        assertThat(ask("x")).isEqualTo(StubChatModel.DEFAULT_REPLY);
    }

    @Test
    void everyPromptIsRecorded() {
        ask("first");
        ask("second");

        assertThat(stub.prompts()).hasSize(2);
        assertThat(stub.prompts().get(1).getContents()).contains("second");
    }
}
