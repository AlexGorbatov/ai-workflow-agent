package com.altronixsoft.workflow.mockrates;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param lanes city pairs with their distance; a lane works in both directions. Names are lower-case, ASCII
 * @param carriers {@code cost = baseEurPerKm * km + perKgEurPerKm * km * weightKg}, {@code days = ceil(km / kmPerDay)}
 * @param simulate how the chaos header behaves
 */
@ConfigurationProperties("rates")
public record RatesProperties(List<Lane> lanes, List<Carrier> carriers, Simulate simulate) {

    public RatesProperties {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        carriers = carriers == null ? List.of() : List.copyOf(carriers);
        simulate = simulate == null ? new Simulate(null) : simulate;
    }

    public record Lane(String a, String b, int distanceKm) {}

    public record Carrier(String name, BigDecimal baseEurPerKm, BigDecimal perKgEurPerKm, int kmPerDay) {}

    /** @param slowDelay how long {@code X-Simulate: slow} holds the answer back */
    public record Simulate(Duration slowDelay) {

        public Simulate {
            slowDelay = slowDelay == null ? Duration.ofSeconds(8) : slowDelay;
        }
    }
}
