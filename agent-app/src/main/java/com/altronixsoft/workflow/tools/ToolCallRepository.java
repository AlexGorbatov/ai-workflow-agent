package com.altronixsoft.workflow.tools;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {

    List<ToolCall> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
