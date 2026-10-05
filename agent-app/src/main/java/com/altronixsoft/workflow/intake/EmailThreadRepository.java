package com.altronixsoft.workflow.intake;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailThreadRepository extends JpaRepository<EmailThread, String> {

    List<EmailThread> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
