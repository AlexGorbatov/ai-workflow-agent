package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    Optional<WorkflowInstance> findByWorkflowTypeAndBusinessKey(String workflowType, String businessKey);

    Page<WorkflowInstance> findByState(QuoteState state, Pageable pageable);
}
