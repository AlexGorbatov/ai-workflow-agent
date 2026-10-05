package com.altronixsoft.workflow.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    Optional<OutboxEntry> findByDedupeKey(String dedupeKey);

    List<OutboxEntry> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);

    /** Due rows, locked so that two relays never take the same one; rows another relay holds are skipped. */
    @Query(value = """
                    select * from outbox
                    where status = 'PENDING' and next_attempt_at <= :now
                    order by created_at
                    limit :batch
                    for update skip locked
                    """, nativeQuery = true)
    List<OutboxEntry> lockDue(@Param("now") Instant now, @Param("batch") int batch);
}
