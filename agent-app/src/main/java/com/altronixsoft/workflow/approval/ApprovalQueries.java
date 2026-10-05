package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.llm.ApprovalSummarizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The read side of approvals. A task's summary is made by the model on first view and kept. */
@Service
public class ApprovalQueries {

    private final ApprovalTaskRepository tasks;
    private final WorkflowInstanceRepository instances;
    private final ApprovalSummarizer summarizer;
    private final JdbcClient jdbc;

    ApprovalQueries(
            ApprovalTaskRepository tasks,
            WorkflowInstanceRepository instances,
            ApprovalSummarizer summarizer,
            JdbcClient jdbc) {
        this.tasks = tasks;
        this.instances = instances;
        this.summarizer = summarizer;
        this.jdbc = jdbc;
    }

    /** Tasks with this status, or the pending ones (OPEN and ESCALATED) when it is null. Two queries in all. */
    public List<ApprovalViews.Item> list(ApprovalStatus status) {
        List<ApprovalTask> found = status == null
                ? tasks.findByStatusInOrderByDueAtAsc(List.of(ApprovalStatus.OPEN, ApprovalStatus.ESCALATED))
                : tasks.findByStatusInOrderByDueAtAsc(List.of(status));
        Map<UUID, WorkflowInstance> byId =
                instances
                        .findAllById(found.stream()
                                .map(ApprovalTask::getInstanceId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(WorkflowInstance::getId, Function.identity()));
        return found.stream()
                .map(t -> {
                    WorkflowInstance i = byId.get(t.getInstanceId());
                    return ApprovalViews.Item.of(t, i == null ? null : i.getContext());
                })
                .toList();
    }

    /** The task with its summary, made by the model now if this is the first view. */
    public ApprovalViews.Detail get(UUID id) {
        return detail(id, true);
    }

    /** The task as it is, without asking the model (after a decision, nobody needs a briefing). */
    public ApprovalViews.Detail view(UUID id) {
        return detail(id, false);
    }

    private ApprovalViews.Detail detail(UUID id, boolean summarize) {
        ApprovalTask task = tasks.findById(id).orElseThrow(() -> new ApprovalTaskNotFoundException(id));
        WorkflowInstance instance =
                instances.findById(task.getInstanceId()).orElseThrow(() -> new ApprovalTaskNotFoundException(id));
        String summary = task.getSummary();
        if (summary == null && summarize) {
            summary = summarizer.summarize(instance.getId(), instance.getContext(), task.getReasons());
            if (summary != null) {
                keep(id, summary);
            }
        }
        return ApprovalViews.Detail.of(task, instance.getState(), instance.getContext(), summary);
    }

    /** First summary wins; written beside the entity so that it never conflicts with a decision. */
    private void keep(UUID id, String summary) {
        jdbc.sql("update approval_task set summary = :summary where id = :id and summary is null")
                .param("summary", summary)
                .param("id", id)
                .update();
    }
}
