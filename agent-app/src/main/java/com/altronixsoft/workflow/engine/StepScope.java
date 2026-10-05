package com.altronixsoft.workflow.engine;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tells code running inside a step (model audit, tool gateway) which instance and which execution it
 * belongs to, without passing ids through every call.
 */
public final class StepScope {

    public record Current(UUID instanceId, UUID stepExecutionId) {}

    private static final ScopedValue<Current> CURRENT = ScopedValue.newInstance();

    private StepScope() {}

    public static <T> T call(Current current, Supplier<T> body) {
        return ScopedValue.where(CURRENT, current).call(body::get);
    }

    public static Optional<Current> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
