package com.altronixsoft.workflow.llm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
