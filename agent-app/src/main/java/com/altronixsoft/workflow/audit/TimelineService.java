package com.altronixsoft.workflow.audit;

import com.altronixsoft.workflow.approval.ApprovalTask;
import com.altronixsoft.workflow.approval.ApprovalTaskRepository;
import com.altronixsoft.workflow.engine.InstanceNotFoundException;
import com.altronixsoft.workflow.engine.StepExecution;
import com.altronixsoft.workflow.engine.StepExecutionRepository;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.intake.EmailThread;
import com.altronixsoft.workflow.intake.EmailThreadRepository;
import com.altronixsoft.workflow.intake.MailDirection;
import com.altronixsoft.workflow.llm.LlmCall;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.outbox.OutboxEntry;
import com.altronixsoft.workflow.outbox.OutboxRepository;
import com.altronixsoft.workflow.tools.ToolCall;
import com.altronixsoft.workflow.tools.ToolCallRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds an instance's timeline: one query per source (steps, model calls, tool calls, mails in, mails out,
 * approval tasks), each by instance id, merged in memory and sorted by time. The number of queries does not
 * grow with the number of steps. Texts (prompts, completions, mails) are left out; a model call's full text has
 * its own endpoint.
 */
@Service
public class TimelineService {

    private final WorkflowInstanceRepository instances;
    private final StepExecutionRepository steps;
    private final LlmCallRepository llmCalls;
    private final ToolCallRepository toolCalls;
    private final EmailThreadRepository threads;
    private final OutboxRepository outbox;
    private final ApprovalTaskRepository approvals;

    TimelineService(
            WorkflowInstanceRepository instances,
            StepExecutionRepository steps,
            LlmCallRepository llmCalls,
            ToolCallRepository toolCalls,
            EmailThreadRepository threads,
            OutboxRepository outbox,
            ApprovalTaskRepository approvals) {
        this.instances = instances;
        this.steps = steps;
        this.llmCalls = llmCalls;
        this.toolCalls = toolCalls;
        this.threads = threads;
        this.outbox = outbox;
        this.approvals = approvals;
    }

    @Transactional(readOnly = true)
    public Timeline timeline(UUID instanceId) {
        WorkflowInstance instance =
                instances.findById(instanceId).orElseThrow(() -> new InstanceNotFoundException(instanceId));
        List<TimelineEvent> events = new ArrayList<>();
        steps.findByInstanceIdOrderByStartedAtAsc(instanceId).forEach(s -> events.add(step(s)));
        List<LlmCall> calls = llmCalls.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        calls.forEach(c -> events.add(llm(c)));
        toolCalls.findByInstanceIdOrderByCreatedAtAsc(instanceId).forEach(t -> events.add(tool(t)));
        threads.findByInstanceIdOrderByCreatedAtAsc(instanceId).stream()
                .filter(t -> t.getDirection() == MailDirection.IN)
                .forEach(t -> events.add(mailIn(t)));
        outbox.findByInstanceIdOrderByCreatedAtAsc(instanceId).forEach(o -> events.add(mailOut(o)));
        approvals.findByInstanceIdOrderByCreatedAtAsc(instanceId).forEach(a -> events.add(approval(a)));
        events.sort(Comparator.comparing(TimelineEvent::at));

        return new Timeline(
                instanceId,
                instance.getState(),
                instance.getCreatedAt(),
                instance.getUpdatedAt(),
                calls.stream()
                        .map(LlmCall::getPromptTokens)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .sum(),
                calls.stream()
                        .map(LlmCall::getCompletionTokens)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .sum(),
                calls.stream()
                        .map(LlmCall::getCostEur)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                List.copyOf(events));
    }

    private static TimelineEvent step(StepExecution s) {
        Map<String, Object> d = details();
        d.put("stepExecutionId", s.getId());
        d.put("attempt", s.getAttempt());
        d.put("finishedAt", s.getFinishedAt());
        if (s.getFinishedAt() != null) {
            d.put(
                    "durationMs",
                    Duration.between(s.getStartedAt(), s.getFinishedAt()).toMillis());
        }
        d.put("error", s.getError());
        return new TimelineEvent(
                s.getStartedAt(),
                "STEP",
                s.getStep() + " #" + s.getAttempt(),
                s.getStatus().name(),
                d);
    }

    private static TimelineEvent llm(LlmCall c) {
        Map<String, Object> d = details();
        d.put("llmCallId", c.getId());
        d.put("stepExecutionId", c.getStepExecutionId());
        d.put("model", c.getModel());
        d.put("promptVersion", c.getPromptVersion());
        d.put("promptTokens", c.getPromptTokens());
        d.put("completionTokens", c.getCompletionTokens());
        d.put("costEur", c.getCostEur());
        d.put("latencyMs", c.getLatencyMs());
        d.put("error", c.getError());
        String title = c.getPromptVersion() == null ? "model call" : c.getPromptVersion();
        return new TimelineEvent(c.getCreatedAt(), "LLM_CALL", title, c.getError() == null ? "OK" : "ERROR", d);
    }

    private static TimelineEvent tool(ToolCall t) {
        Map<String, Object> d = details();
        d.put("toolCallId", t.getId());
        d.put("stepExecutionId", t.getStepExecutionId());
        d.put("kind", t.getKind());
        d.put("durationMs", t.getDurationMs());
        d.put("idempotencyKey", t.getIdempotencyKey());
        d.put("error", t.getError());
        return new TimelineEvent(
                t.getCreatedAt(), "TOOL_CALL", t.getTool(), t.getStatus().name(), d);
    }

    private static TimelineEvent mailIn(EmailThread t) {
        Map<String, Object> d = details();
        d.put("messageId", t.getMessageId());
        return new TimelineEvent(t.getCreatedAt(), "EMAIL_IN", "mail received", "RECEIVED", d);
    }

    private static TimelineEvent mailOut(OutboxEntry o) {
        Map<String, Object> d = details();
        d.put("outboxId", o.getId());
        d.put("to", o.getPayload() == null ? null : o.getPayload().to());
        d.put("messageId", o.getPayload() == null ? null : o.getPayload().messageId());
        d.put("attempts", o.getAttempts());
        d.put("sentAt", o.getSentAt());
        d.put("error", o.getLastError());
        String kind = o.getDedupeKey().substring(o.getDedupeKey().indexOf(':') + 1);
        return new TimelineEvent(
                o.getCreatedAt(), "EMAIL_OUT", kind, o.getStatus().name(), d);
    }

    private static TimelineEvent approval(ApprovalTask a) {
        Map<String, Object> d = details();
        d.put("approvalTaskId", a.getId());
        d.put("round", a.getRound());
        d.put("reasons", a.getReasons());
        d.put("dueAt", a.getDueAt());
        d.put("decidedBy", a.getDecidedBy());
        d.put("decidedAt", a.getDecidedAt());
        d.put("approvedPrice", a.getApprovedPrice());
        return new TimelineEvent(
                a.getCreatedAt(),
                "APPROVAL",
                a.getKind().name() + " round " + a.getRound(),
                a.getStatus().name(),
                d);
    }

    /** Keeps nulls out of the JSON without a second pass. */
    private static Map<String, Object> details() {
        return new LinkedHashMap<>() {
            @Override
            public Object put(String key, Object value) {
                return value == null ? null : super.put(key, value);
            }
        };
    }
}
