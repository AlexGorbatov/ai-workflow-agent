package com.altronixsoft.workflow.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.StubChatModel;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import com.altronixsoft.workflow.tools.ToolCall;
import com.altronixsoft.workflow.tools.ToolCallRepository;
import com.altronixsoft.workflow.tools.ToolCallStatus;
import com.altronixsoft.workflow.tools.ToolGateway;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The Investigator's boundaries: no email text, no write tool, five tool calls at most, never fails the instance. */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
@Import(MockApps.class)
class InvestigatorStepIT {

    private static final Investigation ANSWER = new Investigation(
            "Two CRM customers share the sender's domain.",
            "accounting@polmarket.test is not a contact of either customer.",
            "Ask the sender which company they order for, or add them as a contact in the CRM.",
            List.of("findCustomersByEmail returned C-1001 and C-1007"));

    @Autowired
    InvestigatorStep step;

    @Autowired
    StubChatModel model;

    @Autowired
    ToolCallRepository calls;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** Sample 09: an unknown sender on polmarket.test, where the CRM has two customers. */
    private static QuoteContext ambiguous() {
        return QuoteContext.of(SampleEmails.read("09-not-a-request.eml")).withError("AMBIGUOUS_CUSTOMER");
    }

    private static List<AssistantMessage.ToolCall> lookups(int n) {
        return IntStream.rangeClosed(1, n)
                .mapToObj(i -> new AssistantMessage.ToolCall(
                        "call-" + i, "function", "findCustomersByEmail", "{\"email\":\"accounting@polmarket.test\"}"))
                .toList();
    }

    @Test
    void theModelExplainsWithToolsAndTheInstanceWaitsForAPerson() {
        model.whenPromptContains("AMBIGUOUS_CUSTOMER")
                .callToolsThenReplyWith(lookups(1), json.writeValueAsString(ANSWER));
        UUID instanceId = UUID.randomUUID();

        StepResult result = step.execute(instanceId, ambiguous());

        assertThat(result).isInstanceOfSatisfying(StepResult.Wait.class, wait -> {
            assertThat(wait.waitState()).isEqualTo(QuoteState.AWAIT_APPROVAL);
            assertThat(wait.timeout()).isEqualTo(Duration.ofHours(24));
            assertThat(wait.ctx().investigation()).isEqualTo(ANSWER);
            assertThat(wait.ctx().flags()).doesNotContain(InvestigatorStep.INVESTIGATION_FAILED);
        });
        assertThat(calls.findByInstanceIdOrderByCreatedAtAsc(instanceId))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getTool()).isEqualTo("findCustomersByEmail");
                    assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK);
                });
    }

    @Test
    void theModelSeesNeitherTheEmailTextNorTheWriteTool() {
        model.replyWith(json.writeValueAsString(ANSWER));
        QuoteContext ctx = ambiguous();

        step.execute(UUID.randomUUID(), ctx);

        Prompt prompt = model.prompts().getFirst();
        assertThat(prompt.getContents())
                .contains("AMBIGUOUS_CUSTOMER", "accounting@polmarket.test")
                .doesNotContain("INV-2026-0931", "resend invoice", ctx.email().subject());
        assertThat(prompt.getOptions())
                .isInstanceOfSatisfying(
                        ToolCallingChatOptions.class,
                        options -> assertThat(options.getToolCallbacks())
                                .extracting(c -> c.getToolDefinition().name())
                                .containsExactlyInAnyOrder("findCustomersByEmail", "getCreditStatus", "getRates")
                                .doesNotContain("createOpportunity"));
    }

    @Test
    void sevenRequestedCallsGetFiveAnswersAndTwoBudgetExceeded() {
        model.whenPromptContains("AMBIGUOUS_CUSTOMER")
                .callToolsThenReplyWith(lookups(7), json.writeValueAsString(ANSWER));
        UUID instanceId = UUID.randomUUID();

        step.execute(instanceId, ambiguous());

        List<ToolCall> executed = calls.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        assertThat(executed)
                .hasSize(5)
                .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ToolCallStatus.OK));
        List<String> answers = model.prompts().getLast().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .toList();
        assertThat(answers).hasSize(7);
        assertThat(answers.stream().filter(ToolGateway.BUDGET_EXCEEDED::equals)).hasSize(2);
    }

    @Test
    void aFailingModelLeavesAnEmptyInvestigationAndAFlagInsteadOfFailing() {
        model.failWith(new IllegalStateException("model is down"));

        StepResult result = step.execute(UUID.randomUUID(), ambiguous());

        assertThat(result).isInstanceOfSatisfying(StepResult.Wait.class, wait -> {
            assertThat(wait.waitState()).isEqualTo(QuoteState.AWAIT_APPROVAL);
            assertThat(wait.ctx().investigation()).isNull();
            assertThat(wait.ctx().flags()).contains(InvestigatorStep.INVESTIGATION_FAILED);
            assertThat(wait.ctx().error()).isEqualTo("AMBIGUOUS_CUSTOMER");
        });
    }

    @Test
    void anUnusableAnswerCountsAsAFailure() {
        model.replyWith("I could not find anything.");

        StepResult result = step.execute(UUID.randomUUID(), ambiguous());

        assertThat(result)
                .isInstanceOfSatisfying(
                        StepResult.Wait.class,
                        wait -> assertThat(wait.ctx().flags()).contains(InvestigatorStep.INVESTIGATION_FAILED));
    }
}
