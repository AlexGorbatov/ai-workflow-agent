package com.altronixsoft.workflow.approval;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, UUID> {

    List<ApprovalTask> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);

    List<ApprovalTask> findByStatusInOrderByDueAtAsc(List<ApprovalStatus> statuses);

    List<ApprovalTask> findAllByOrderByCreatedAtDesc();
}
