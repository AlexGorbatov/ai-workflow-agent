package com.altronixsoft.workflow.engine;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepExecutionRepository extends JpaRepository<StepExecution, UUID> {

    List<StepExecution> findByInstanceIdOrderByStartedAtAsc(UUID instanceId);

    List<StepExecution> findByInstanceIdAndStatus(UUID instanceId, StepExecutionStatus status);
}
