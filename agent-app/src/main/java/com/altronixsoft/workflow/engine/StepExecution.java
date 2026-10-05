package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One attempt to run a step for an instance: the engine's own record of what happened. */
@Entity
@Table(name = "step_execution")
public class StepExecution {

    @Id
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(nullable = false)
    private String step;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private QuoteContext input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private QuoteContext output;

    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected StepExecution() {}

    public static StepExecution started(
            UUID id, UUID instanceId, String step, int attempt, QuoteContext input, Instant now) {
        StepExecution e = new StepExecution();
        e.id = id;
        e.instanceId = instanceId;
        e.step = step;
        e.attempt = attempt;
        e.status = StepExecutionStatus.RUNNING;
        e.input = input;
        e.startedAt = now;
        return e;
    }

    public void succeed(QuoteContext result, Instant now) {
        this.status = StepExecutionStatus.SUCCEEDED;
        this.output = result;
        this.finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = StepExecutionStatus.FAILED;
        this.error = reason;
        this.finishedAt = now;
    }

    public void abandon(Instant now) {
        this.status = StepExecutionStatus.ABANDONED;
        this.finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getStep() {
        return step;
    }

    public int getAttempt() {
        return attempt;
    }

    public StepExecutionStatus getStatus() {
        return status;
    }

    public QuoteContext getInput() {
        return input;
    }

    public QuoteContext getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
