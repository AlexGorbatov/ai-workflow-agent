package com.altronixsoft.workflow.api;

import com.altronixsoft.workflow.audit.Timeline;
import com.altronixsoft.workflow.audit.TimelineService;
import com.altronixsoft.workflow.engine.InstanceNotFoundException;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.LlmCall;
import com.altronixsoft.workflow.llm.LlmCallRepository;
import com.altronixsoft.workflow.quote.QuoteState;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    InstanceController(WorkflowInstanceRepository instances, TimelineService timelines, LlmCallRepository llmCalls) {
        this.instances = instances;
        this.timelines = timelines;
        this.llmCalls = llmCalls;
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
