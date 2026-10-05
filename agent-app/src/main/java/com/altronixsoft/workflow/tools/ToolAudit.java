package com.altronixsoft.workflow.tools;

import com.altronixsoft.workflow.engine.StepScope;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Runs a gateway call and writes its record, whatever the outcome. Secrets never reach the table. */
@Component
class ToolAudit {

    private static final String REDACTED = "***";

    private final ToolCallRepository calls;
    private final Clock clock;

    ToolAudit(ToolCallRepository calls, Clock clock) {
        this.calls = calls;
        this.clock = clock;
    }

    /** Runs {@code body}; the exception it throws (if any) is recorded and rethrown unchanged. */
    String record(
            CallContext cc,
            String tool,
            Map<String, Object> args,
            ToolKind kind,
            String idempotencyKey,
            Callable<String> body) {
        long started = System.nanoTime();
        try {
            String result = body.call();
            save(cc, tool, args, kind, idempotencyKey, started, result, ToolCallStatus.OK, null);
            return result;
        } catch (ToolDenied e) {
            save(cc, tool, args, kind, idempotencyKey, started, null, ToolCallStatus.DENIED, e.getMessage());
            throw e;
        } catch (ToolCallFailed e) {
            boolean timedOut = e.getCause() instanceof TimeoutException;
            save(
                    cc,
                    tool,
                    args,
                    kind,
                    idempotencyKey,
                    started,
                    null,
                    timedOut ? ToolCallStatus.TIMEOUT : ToolCallStatus.ERROR,
                    e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            save(cc, tool, args, kind, idempotencyKey, started, null, ToolCallStatus.ERROR, e.getMessage());
            throw e;
        } catch (Exception e) {
            save(cc, tool, args, kind, idempotencyKey, started, null, ToolCallStatus.ERROR, e.getMessage());
            throw new ToolCallFailed(e.getMessage(), true, e);
        }
    }

    private void save(
            CallContext cc,
            String tool,
            Map<String, Object> args,
            ToolKind kind,
            String key,
            long startedNanos,
            String result,
            ToolCallStatus status,
            String error) {
        UUID execution =
                StepScope.current().map(StepScope.Current::stepExecutionId).orElse(null);
        calls.save(new ToolCall(
                cc.instanceId(),
                execution,
                tool,
                kind,
                redact(args),
                result,
                status,
                key,
                (System.nanoTime() - startedNanos) / 1_000_000,
                error,
                clock.instant()));
    }

    private static Map<String, Object> redact(Map<String, Object> args) {
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.computeIfPresent("approvalToken", (k, v) -> REDACTED);
        return copy;
    }
}
