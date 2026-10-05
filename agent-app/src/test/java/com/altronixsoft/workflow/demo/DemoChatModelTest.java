package com.altronixsoft.workflow.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.FieldValidator;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.NumericGuard;
import com.altronixsoft.workflow.llm.ReplyDraft;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

/** The demo model must make the samples behave as samples/emails/README.md says, and its replies must pass. */
class DemoChatModelTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final DemoChatModel model = new DemoChatModel(json);
    private final FieldValidator validator = new FieldValidator();

    private Extraction understand(String emailText) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You extract freight quote data from a customer email."),
                new UserMessage("<email>\n" + emailText + "\n</email>")));
        return json.readValue(model.call(prompt).getResult().getOutput().getText(), Extraction.class);
    }

    @ParameterizedTest
    @CsvSource({
        "01-happy-path-gold.eml, QUOTE_REQUEST, ''",
        "02-high-value-approver.eml, QUOTE_REQUEST, ''",
        "03-new-customer.eml, QUOTE_REQUEST, ''",
        "04-missing-fields.eml, QUOTE_REQUEST, pallets weightKg pickupDate",
        "06-dangerous-goods-adr.eml, QUOTE_REQUEST, ''",
        "07-prompt-injection.eml, QUOTE_REQUEST, ''",
        "08-german-reefer.eml, QUOTE_REQUEST, ''",
        "09-not-a-request.eml, OTHER, ''",
        "11-ukrainian-full.eml, QUOTE_REQUEST, ''",
        "12-missing-weight.eml, QUOTE_REQUEST, weightKg",
        "13-missing-date.eml, QUOTE_REQUEST, pickupDate",
        "14-shipment-status-question.eml, SHIPMENT_STATUS, ''",
        "15-hidden-injection.eml, QUOTE_REQUEST, ''"
    })
    void everySampleIsReadAsItsReadmeSays(String sample, Intent intent, String missing) {
        Extraction x = understand(QuoteContext.of(SampleEmails.read(sample)).fullText());

        assertThat(x.intent()).isEqualTo(intent);
        if (intent == Intent.QUOTE_REQUEST) {
            assertThat(validator.missing(x))
                    .containsExactlyInAnyOrder(missing.isBlank() ? new String[0] : missing.split(" "));
        }
    }

    @Test
    void theClarificationReplyCompletesTheRequestItAnswers() {
        QuoteContext ctx = QuoteContext.of(SampleEmails.read("04-missing-fields.eml"))
                .withReply(SampleEmails.read("05-clarification-reply.eml").body());

        Extraction x = understand(ctx.fullText());

        assertThat(validator.missing(x)).isEmpty();
        assertThat(x.pallets()).isEqualTo(8);
    }

    @Test
    void injectionSamplesAreMarked() {
        assertThat(understand(SampleEmails.text("07-prompt-injection.eml")).containsInstructionsToAssistant())
                .isTrue();
        assertThat(understand(SampleEmails.text("15-hidden-injection.eml")).containsInstructionsToAssistant())
                .isTrue();
    }

    @Test
    void theReplyStatesThePriceAndDatesFromTheFactsSoTheGuardPassesIt() {
        QuoteFacts facts = new QuoteFacts(
                "de",
                "FrischKette GmbH",
                "Hamburg",
                "Wien",
                new BigDecimal("9800"),
                14,
                "dairy products",
                LocalDate.of(2026, 10, 7),
                2,
                new BigDecimal("1385"),
                "EUR",
                LocalDate.of(2026, 10, 19));
        // as ChatClient sends it: the facts, then the format instructions with a JSON schema of their own
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You write the reply of a freight forwarder (Nordline Logistics)."),
                new UserMessage("Facts:\n" + json.writeValueAsString(facts)
                        + "\nYour response should be in JSON format: {\"type\":\"object\"}")));

        ReplyDraft draft =
                json.readValue(model.call(prompt).getResult().getOutput().getText(), ReplyDraft.class);

        assertThat(draft.body()).contains("1385.00 EUR", "2026-10-19");
        assertThat(new NumericGuard().check(draft.body(), facts)).isEmpty();
    }
}
