package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.engine.StepScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AuditAdvisorIT {

    @Autowired
    ChatClient chat;

    @Autowired
    StubChatModel model;

    @Autowired
    LlmCallRepository calls;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    private List<LlmCall> callsContaining(String marker) {
        return calls.findAll().stream()
                .filter(c -> c.getRequest().contains(marker))
                .toList();
    }

    @Test
    void aCallInsideAStepIsRecordedAgainstThatStep() {
        String marker = "marker-" + UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        model.replyWith("{\"ok\":true}");

        String answer = StepScope.call(
                new StepScope.Current(instance, execution),
                () -> chat.prompt()
                        .user("Extract the data. " + marker)
                        .advisors(a -> a.param(AuditAdvisor.PROMPT_VERSION, "understand-v1"))
                        .call()
                        .content());

        assertThat(answer).isEqualTo("{\"ok\":true}");
        List<LlmCall> rows = callsContaining(marker);
        assertThat(rows).hasSize(1);
        LlmCall row = rows.get(0);
        assertThat(row.getInstanceId()).isEqualTo(instance);
        assertThat(row.getStepExecutionId()).isEqualTo(execution);
        assertThat(row.getModel()).isEqualTo(StubChatModel.MODEL_NAME);
        assertThat(row.getPromptVersion()).isEqualTo("understand-v1");
        assertThat(row.getPromptTokens()).isPositive();
        assertThat(row.getCompletionTokens()).isPositive();
        assertThat(row.getLatencyMs()).isNotNegative();
        assertThat(row.getRequest()).contains("Extract the data.");
        assertThat(row.getResponse()).isEqualTo("{\"ok\":true}");
        assertThat(row.getError()).isNull();
        // the stub model has no price: recorded as 0, not left empty
        assertThat(row.getCostEur()).isEqualByComparingTo("0");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instance))
                .extracting(LlmCall::getId)
                .contains(row.getId());
    }

    @Test
    void aCallOutsideAStepIsStillRecordedWithoutOne() {
        String marker = "outside-" + UUID.randomUUID();

        chat.prompt().user(marker).call().content();

        List<LlmCall> rows = callsContaining(marker);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getInstanceId()).isNull();
        assertThat(rows.get(0).getStepExecutionId()).isNull();
        assertThat(rows.get(0).getPromptVersion()).isNull();
    }

    @Test
    void aFailedCallIsRecordedAndTheErrorStillReachesTheCaller() {
        String marker = "failing-" + UUID.randomUUID();
        model.failWith(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> chat.prompt().user(marker).call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider unavailable");

        List<LlmCall> rows = callsContaining(marker);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getError()).contains("provider unavailable");
        assertThat(rows.get(0).getResponse()).isNull();
        assertThat(rows.get(0).getPromptTokens()).isNull();
    }

    @Test
    void promptAndAnswerTextNeverReachTheLog(CapturedOutput output) {
        String secret = "secret-customer-text-" + UUID.randomUUID();
        model.replyWith("secret-answer-" + secret);

        chat.prompt().user(secret).call().content();

        assertThat(callsContaining(secret)).hasSize(1);
        assertThat(output.getAll()).doesNotContain(secret);
    }
}
