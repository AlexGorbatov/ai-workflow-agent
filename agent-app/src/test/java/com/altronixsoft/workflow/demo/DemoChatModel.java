package com.altronixsoft.workflow.demo;

import com.altronixsoft.workflow.llm.Extraction;
import com.altronixsoft.workflow.llm.Intent;
import com.altronixsoft.workflow.llm.ReplyDraft;
import com.altronixsoft.workflow.quote.Investigation;
import com.altronixsoft.workflow.quote.QuoteFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model of {@code scripts/demo.sh}: no network, no API key, and answers that make the fifteen sample emails
 * behave as {@code samples/emails/README.md} describes. Extractions are canned per sample (matched by a phrase of
 * the email); replies are written from the facts in the prompt, so the price and the dates are always the ones
 * the code computed and NumericGuard lets them through. Anything else is not a quote request.
 */
public class DemoChatModel implements ChatModel {

    public static final String MODEL_NAME = "demo-model";

    private static final String UNDERSTAND = "You extract freight quote data";
    private static final String RESPOND = "You write the reply of a freight forwarder";
    private static final String STRICT = "STRICT MODE";
    private static final String INVESTIGATE = "You help an operator";
    private static final String SUMMARIZE = "You write a short briefing";

    /**
     * Phrase of the email → what a model would read out of it. Checked in order: the clarification reply (05)
     * comes before the request it answers (04), because the reply is appended to the same email text.
     */
    private static final List<Map.Entry<String, Extraction>> EXTRACTIONS = List.of(
            Map.entry("8 pallets, 4,800 kg", quote("Milano", "Lyon", "4800", 8, "canned tomatoes", "2026-10-08", "en")),
            Map.entry(
                    "12 pallets Warsaw -> Berlin",
                    quote("Warszawa", "Berlin", "7200", 12, "household goods", "2026-10-06", "en")),
            Map.entry(
                    "Gothenburg -> Madrid",
                    quote("Göteborg", "Madrid", "66000", 99, "sawn timber", "2026-10-12", "en")),
            Map.entry("4 palety Lodz -> Praga", quote("Łódź", "Praha", "2000", 4, "bicycle parts", "2026-10-06", "pl")),
            Map.entry("Pallets Milan -> Lyon", quote("Milano", "Lyon", null, null, "canned tomatoes", null, "en")),
            Map.entry(
                    "ADR shipment Duisburg -> Rotterdam",
                    quote("Duisburg", "Rotterdam", "6500", 10, "paint, ADR class 3", "2026-10-07", "en")),
            Map.entry(
                    "Quote Valencia -> Lyon",
                    injected(quote("Valencia", "Lyon", "3100", 6, "shoes", "2026-10-13", "en"))),
            Map.entry(
                    "Kühltransport Hamburg",
                    quote("Hamburg", "Wien", "9800", 14, "dairy products, 2-8 °C", "2026-10-07", "de")),
            Map.entry("Київ", quote("Kyiv", "Wroclaw", "3600", 8, "household appliances", "2026-10-09", "uk")),
            Map.entry("Quote Verona -> Munich", quote("Verona", "München", null, 6, "olive oil", "2026-10-12", "en")),
            Map.entry("Quote Poznan -> Hamburg", quote("Poznań", "Hamburg", "5400", 10, "packaged food", null, "en")),
            Map.entry("Where is my shipment?", other(Intent.SHIPMENT_STATUS)),
            Map.entry(
                    "Quote Hamburg -> Wien, 4 pallets",
                    injected(quote("Hamburg", "Wien", "1900", 4, "printed packaging", "2026-10-15", "en"))));

    private final JsonMapper json;

    public DemoChatModel(JsonMapper json) {
        this.json = json;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getContents();
        String answer;
        if (text.contains(UNDERSTAND)) {
            answer = json.writeValueAsString(understand(text));
        } else if (text.contains(RESPOND) || text.contains(STRICT)) {
            answer = json.writeValueAsString(reply(json.readValue(factsJson(text), QuoteFacts.class)));
        } else if (text.contains(INVESTIGATE)) {
            answer = json.writeValueAsString(new Investigation(
                    "The request could not be priced automatically.",
                    "See the error code and the facts: the lane or the customer data is likely incomplete.",
                    "Check the customer and the lane in the CRM, then retry or answer the customer by hand.",
                    List.of("demo model: no tools were called")));
        } else if (text.contains(SUMMARIZE)) {
            answer = "A customer asks for a transport quote that the policy does not let the agent send on its own; "
                    + "the reasons are on this task. The price comes from the pricing rules, not from the model.";
        } else {
            answer = "{}";
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(MODEL_NAME)
                .usage(new DefaultUsage(tokens(text), tokens(answer)))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    }

    private static Extraction understand(String prompt) {
        return EXTRACTIONS.stream()
                .filter(e -> prompt.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(other(Intent.OTHER));
    }

    private static ReplyDraft reply(QuoteFacts f) {
        String price = f.price().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + f.currency();
        String body = "Hello,\n\n"
                + "thank you for your request. Our price for the transport of " + f.cargoType() + " from "
                + f.origin() + " to " + f.destination() + ", pickup on " + f.pickupDate() + ", is " + price + ".\n"
                + "The offer is valid until " + f.validUntil() + ". Reply to this email to book it.\n\n"
                + "Best regards,\nNordline Logistics";
        return new ReplyDraft("Your quote: " + f.origin() + " - " + f.destination(), body);
    }

    /** The facts JSON after the last "Facts:" of the prompt, up to its closing brace. */
    private static String factsJson(String prompt) {
        int start = prompt.indexOf('{', prompt.lastIndexOf("Facts:"));
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return prompt.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("No facts in the prompt");
    }

    /** A quote request; a {@code null} fact is one the email does not state, and is listed as missing. */
    private static Extraction quote(
            String from, String to, String kg, Integer pallets, String cargo, String pickup, String language) {
        List<String> missing = new ArrayList<>();
        if (kg == null) {
            missing.add("weightKg");
        }
        if (pallets == null) {
            missing.add("pallets");
        }
        if (pickup == null) {
            missing.add("pickupDate");
        }
        return new Extraction(
                Intent.QUOTE_REQUEST,
                from,
                to,
                kg == null ? null : new BigDecimal(kg),
                pallets,
                cargo,
                pickup == null ? null : LocalDate.parse(pickup),
                language,
                missing,
                0.95,
                false);
    }

    private static Extraction injected(Extraction x) {
        return new Extraction(
                x.intent(),
                x.origin(),
                x.destination(),
                x.weightKg(),
                x.pallets(),
                x.cargoType(),
                x.pickupDate(),
                x.language(),
                x.missingFields(),
                x.confidence(),
                true);
    }

    private static Extraction other(Intent intent) {
        return new Extraction(intent, null, null, null, null, null, null, "en", List.of(), 0.95, false);
    }

    /** Tool-calling options, like a real provider's, so ChatClient hands the Investigator its tools. */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    private static int tokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
