package com.altronixsoft.workflow.api;

import com.altronixsoft.workflow.audit.Timeline;
import com.altronixsoft.workflow.audit.TimelineService;
import com.altronixsoft.workflow.engine.InstanceNotFoundException;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.LlmCall;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.quote.QuoteState;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Instances for operators and approvers; a model call's full text for operators (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/instances")
class InstanceController {

    static final int MAX_PAGE_SIZE = 100;

    private final WorkflowInstanceRepository instances;
    private final TimelineService timelines;
    private final LlmCallRepository llmCalls;
    private final JdbcClient jdbc;

    InstanceController(
            WorkflowInstanceRepository instances,
            TimelineService timelines,
            LlmCallRepository llmCalls,
            JdbcClient jdbc) {
        this.instances = instances;
        this.timelines = timelines;
        this.llmCalls = llmCalls;
        this.jdbc = jdbc;
    }

    /** Counts for a dashboard: instances per state (every state, zero included) and approvals waiting. */
    @GetMapping("/summary")
    InstanceViews.Summary summary() {
        Map<QuoteState, Long> byState = new EnumMap<>(QuoteState.class);
        for (QuoteState state : QuoteState.values()) {
            byState.put(state, 0L);
        }
        jdbc.sql("select state, count(*) as n from workflow_instance group by state")
                .query((rs, row) -> Map.entry(QuoteState.valueOf(rs.getString("state")), rs.getLong("n")))
                .list()
                .forEach(e -> byState.put(e.getKey(), e.getValue()));
        long openApprovals = jdbc.sql("select count(*) from approval_task where status in ('OPEN', 'ESCALATED')")
                .query(Long.class)
                .single();
        long total = byState.values().stream().mapToLong(Long::longValue).sum();
        return new InstanceViews.Summary(total, byState, openApprovals);
    }

    /** Newest first; {@code state} filters, {@code page} starts at 0. */
    @GetMapping
    InstanceViews.Page list(
            @RequestParam(required = false) QuoteState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest request = PageRequest.of(
                Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"));
        org.springframework.data.domain.Page<WorkflowInstance> found =
                state == null ? instances.findAll(request) : instances.findByState(state, request);
        return new InstanceViews.Page(
                found.map(InstanceViews.Item::of).getContent(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    @GetMapping("/{id}")
    InstanceViews.Detail get(@PathVariable UUID id) {
        return InstanceViews.Detail.of(instances.findById(id).orElseThrow(() -> new InstanceNotFoundException(id)));
    }

    @GetMapping("/{id}/timeline")
    Timeline timeline(@PathVariable UUID id) {
        return timelines.timeline(id);
    }

    @GetMapping("/{id}/llm-calls/{callId}")
    InstanceViews.LlmCallText llmCall(@PathVariable UUID id, @PathVariable UUID callId) {
        LlmCall call = llmCalls.findById(callId)
                .filter(c -> id.equals(c.getInstanceId()))
                .orElseThrow(() -> new InstanceNotFoundException(id));
        return new InstanceViews.LlmCallText(
                call.getId(),
                call.getModel(),
                call.getPromptVersion(),
                call.getRequest(),
                call.getResponse(),
                call.getError(),
                call.getCreatedAt());
    }
}
