package com.altronixsoft.workflow.tools;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@code getRates}: the rates service's {@code GET /rates} as a tool. A lane the service does not serve is an
 * empty list, not an error; an outage (5xx, no connection) is worth retrying, a rejected request (4xx) is not.
 */
@Component
class RatesTool {

    static final String NAME = "getRates";

    private static final ParameterizedTypeReference<List<RateDto>> RATES = new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final String simulate;

    RatesTool(RestClient.Builder builder, ToolsProperties properties) {
        this.http = builder.clone().baseUrl(properties.ratesUrl()).build();
        this.simulate = properties.ratesSimulate();
    }

    ToolCallback callback() {
        return FunctionToolCallback.builder(NAME, (Function<RatesQuery, List<RateDto>>) this::rates)
                .description("Carrier rates for a lane: cost in EUR and transit days per carrier, cheapest first. "
                        + "An empty list means the lane is not served.")
                .inputType(RatesQuery.class)
                .build();
    }

    List<RateDto> rates(RatesQuery query) {
        try {
            List<RateDto> rates = http.get()
                    .uri(u -> u.path("/rates")
                            .queryParam("origin", query.origin())
                            .queryParam("destination", query.destination())
                            .queryParam("weightKg", query.weightKg())
                            .build())
                    .headers(h -> {
                        if (simulate != null && !simulate.isBlank()) {
                            h.set("X-Simulate", simulate);
                        }
                    })
                    .retrieve()
                    .body(RATES);
            return rates == null ? List.of() : rates;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return List.of();
            }
            throw new ToolCallFailed(
                    "Rates service answered " + e.getStatusCode().value(),
                    e.getStatusCode().is5xxServerError(),
                    e);
        } catch (ResourceAccessException e) {
            throw new ToolCallFailed("Rates service is not reachable: " + e.getMessage(), true, e);
        }
    }

    record RatesQuery(
            @ToolParam(description = "Origin city, for example Warszawa")
            String origin,

            @ToolParam(description = "Destination city, for example Berlin")
            String destination,

            @ToolParam(description = "Total weight in kilograms")
            BigDecimal weightKg) {}

    record RateDto(String carrier, BigDecimal cost, String currency, int transitDays) {}
}
