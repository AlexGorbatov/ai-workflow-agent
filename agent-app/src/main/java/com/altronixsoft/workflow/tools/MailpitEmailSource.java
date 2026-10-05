package com.altronixsoft.workflow.tools;

import com.altronixsoft.workflow.intake.EmailSource;
import com.altronixsoft.workflow.quote.InboundEmail;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads the quotes inbox from Mailpit's REST API. */
@Component
class MailpitEmailSource implements EmailSource {

    private static final int PAGE = 100;

    private final RestClient http;
    private final String inbox;

    MailpitEmailSource(RestClient.Builder builder, MailProperties properties) {
        this.http = builder.baseUrl(properties.mailpitUrl()).build();
        this.inbox = properties.inbox();
    }

    @Override
    public List<InboundEmail> fetchNew() {
        SearchResult found = http.get()
                .uri("/api/v1/search?query={q}&limit={n}", "to:" + inbox, PAGE)
                .retrieve()
                .body(SearchResult.class);
        if (found == null || found.messages() == null) {
            return List.of();
        }
        return found.messages().stream()
                .sorted(Comparator.comparing(MessageSummary::created))
                .map(this::load)
                .toList();
    }

    private InboundEmail load(MessageSummary summary) {
        MessageDetail detail =
                http.get().uri("/api/v1/message/{id}", summary.id()).retrieve().body(MessageDetail.class);
        Map<String, List<String>> headers = http.get()
                .uri("/api/v1/message/{id}/headers", summary.id())
                .retrieve()
                .body(HEADERS);
        return new InboundEmail(
                bracketed(summary.messageId()),
                inReplyTo(headers),
                summary.from().address(),
                summary.subject(),
                detail == null || detail.text() == null ? "" : detail.text().strip(),
                summary.created());
    }

    /** {@code In-Reply-To} if present, otherwise the last id of {@code References} (the direct parent). */
    private static String inReplyTo(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        String direct = firstId(headers.get("In-Reply-To"));
        if (direct != null) {
            return direct;
        }
        List<String> references = headers.get("References");
        if (references == null || references.isEmpty()) {
            return null;
        }
        String[] ids = references.get(0).trim().split("\\s+");
        return ids.length == 0 || ids[ids.length - 1].isBlank() ? null : bracketed(ids[ids.length - 1]);
    }

    private static String firstId(List<String> values) {
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return null;
        }
        return bracketed(values.get(0).trim());
    }

    private static String bracketed(String id) {
        if (id == null) {
            return null;
        }
        return id.startsWith("<") ? id : "<" + id + ">";
    }

    private static final ParameterizedTypeReference<Map<String, List<String>>> HEADERS =
            new ParameterizedTypeReference<>() {};

    private record SearchResult(@JsonProperty("messages") List<MessageSummary> messages) {}

    private record Address(@JsonProperty("Address") String address) {}

    private record MessageSummary(
            @JsonProperty("ID") String id,
            @JsonProperty("MessageID") String messageId,
            @JsonProperty("From") Address from,
            @JsonProperty("Subject") String subject,
            @JsonProperty("Created") Instant created) {}

    private record MessageDetail(@JsonProperty("Text") String text) {}
}
