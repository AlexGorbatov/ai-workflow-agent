package com.altronixsoft.workflow.engine;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    Optional<WorkflowInstance> findByWorkflowTypeAndBusinessKey(String workflowType, String businessKey);
}
