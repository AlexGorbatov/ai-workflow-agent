package com.altronixsoft.workflow.quote;

import com.altronixsoft.workflow.engine.Step;
import com.altronixsoft.workflow.engine.StepResult;
import com.altronixsoft.workflow.tools.CallContext;
import com.altronixsoft.workflow.tools.ToolGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prices the request: carrier rates through the gateway, the cheapest carrier that delivers in time, then
 * {@link PricingCalculator}. Its inputs are the structured request and the CRM customer, never the email text.
 * No usable rate sends the instance to investigation.
 */
@Component
@ConditionalOnProperty(name = "workflow.real-steps", havingValue = "true", matchIfMissing = true)
public class PriceStep implements Step {

    public static final String NO_RATES = "NO_RATES";
    public static final String DANGEROUS_GOODS = "DANGEROUS_GOODS";

    private static final TypeReference<List<CarrierRate>> RATES = new TypeReference<>() {};

    private final ToolGateway gateway;
    private final PricingCalculator calculator;
    private final PricingRules rules;
    private final JsonMapper json;
    private final Clock clock;

    PriceStep(ToolGateway gateway, PricingCalculator calculator, PricingRules rules, JsonMapper json, Clock clock) {
        this.gateway = gateway;
        this.calculator = calculator;
        this.rules = rules;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public QuoteState handles() {
        return QuoteState.ENRICHED;
    }

    @Override
    public StepResult execute(UUID instanceId, QuoteContext ctx) {
        QuoteRequest request = ctx.request();
        Map<String, Object> lane = Map.of(
                "origin", request.origin(),
                "destination", request.destination(),
                "weightKg", request.weightKg());
        List<CarrierRate> rates =
                json.readValue(gateway.callFromStep("getRates", lane, CallContext.of(instanceId, handles())), RATES);
        QuoteContext withRates = ctx.withRates(rates);

        Optional<CarrierRate> cheapest = rates.stream()
                .filter(r -> r.transitDays() <= rules.maxTransitDays())
                .min(Comparator.comparing(CarrierRate::cost));
        if (cheapest.isEmpty()) {
            return new StepResult.Next(QuoteState.INVESTIGATING, withRates.withError(NO_RATES));
        }

        CustomerTier tier = ctx.customer() == null ? null : ctx.customer().tier();
        boolean dangerous = request.isDangerous();
        Quote quote = calculator.price(cheapest.get(), tier, dangerous, LocalDate.now(clock));
        QuoteContext priced = withRates.withQuote(quote);
        return new StepResult.Next(QuoteState.PRICED, dangerous ? priced.withFlag(DANGEROUS_GOODS) : priced);
    }
}
