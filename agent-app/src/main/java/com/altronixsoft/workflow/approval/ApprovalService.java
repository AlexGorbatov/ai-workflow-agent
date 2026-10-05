package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.approvaltoken.ApprovalClaims;
import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.engine.InvalidSignalException;
import com.altronixsoft.workflow.engine.Signal;
import com.altronixsoft.workflow.engine.WorkflowEngine;
import com.altronixsoft.workflow.engine.WorkflowInstance;
import com.altronixsoft.workflow.engine.WorkflowInstanceRepository;
import com.altronixsoft.workflow.policy.PolicyRules;
import com.altronixsoft.workflow.quote.Quote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies an approver's decision. The task's new status and the signal to the instance are one transaction:
 * either both happen or neither. A task that is no longer pending, or that someone else decides at the same
 * moment (the task's version moved), is a conflict, never a second signal.
 */
@Service
public class ApprovalService {

    private final ApprovalTaskRepository tasks;
    private final WorkflowInstanceRepository instances;
    private final WorkflowEngine engine;
    private final ApprovalTokens tokens;
    private final PolicyRules policy;
    private final TransactionTemplate tx;
    private final Clock clock;

    ApprovalService(
            ApprovalTaskRepository tasks,
            WorkflowInstanceRepository instances,
            WorkflowEngine engine,
            ApprovalTokens tokens,
            PolicyRules policy,
            TransactionTemplate tx,
            Clock clock) {
        this.tasks = tasks;
        this.instances = instances;
        this.engine = engine;
        this.tokens = tokens;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    /** @param approver the person deciding (the token's {@code preferred_username}) */
    public ApprovalTask decide(UUID taskId, Decision decision, String approver) {
        try {
            return tx.execute(status -> apply(taskId, decision, approver));
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            throw new ApprovalConflictException("Task " + taskId + " was decided by someone else");
        } catch (InvalidSignalException stale) {
            throw new ApprovalConflictException("The instance is no longer waiting for this decision");
        }
    }

    private ApprovalTask apply(UUID taskId, Decision decision, String approver) {
        ApprovalTask task = tasks.findById(taskId).orElseThrow(() -> new ApprovalTaskNotFoundException(taskId));
        if (!task.getStatus().isPending()) {
            throw new ApprovalConflictException("Task " + taskId + " is already " + task.getStatus());
        }
        Instant now = clock.instant();
        switch (decision.action()) {
            case APPROVE -> {
                BigDecimal price = approvedPrice(task, decision);
                String token = tokens.issue(new ApprovalClaims(
                        task.getInstanceId(),
                        ApprovalActions.CREATE_OPPORTUNITY,
                        price,
                        approver,
                        now.plus(policy.tokenValidity())));
                task.decide(ApprovalStatus.APPROVED, approver, now, decision.comment(), price);
                tasks.saveAndFlush(task);
                engine.signal(task.getInstanceId(), new Signal.Approved(token, price));
            }
            case REJECT -> {
                task.decide(ApprovalStatus.REJECTED, approver, now, decision.comment(), null);
                tasks.saveAndFlush(task);
                engine.signal(task.getInstanceId(), new Signal.Rejected(decision.comment()));
            }
            case RETRY -> {
                task.decide(ApprovalStatus.RETRIED, approver, now, decision.comment(), null);
                tasks.saveAndFlush(task);
                engine.signal(task.getInstanceId(), new Signal.Retry(decision.retryFrom()));
            }
        }
        return task;
    }

    /** The edited price if given, else the quoted one; never below the carrier cost. */
    private BigDecimal approvedPrice(ApprovalTask task, Decision decision) {
        WorkflowInstance instance = instances
                .findById(task.getInstanceId())
                .orElseThrow(() -> new ApprovalTaskNotFoundException(task.getId()));
        Quote quote = instance.getContext().quote();
        if (quote == null) {
            throw new UnprocessableDecisionException("There is no quote to approve; reject or retry instead");
        }
        BigDecimal price = decision.editedPrice() != null ? decision.editedPrice() : quote.price();
        if (price.compareTo(quote.cost()) < 0) {
            throw new UnprocessableDecisionException("The price " + price.toPlainString() + " is below the cost "
                    + quote.cost().toPlainString());
        }
        return price;
    }
}
